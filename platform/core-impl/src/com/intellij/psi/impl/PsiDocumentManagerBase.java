// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.ASTNode;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.DocumentRunnable;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.ex.PrioritizedInternalDocumentListener;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.editor.impl.EditorDocumentPriorities;
import com.intellij.openapi.editor.impl.FrozenDocument;
import com.intellij.openapi.editor.impl.event.RetargetRangeMarkers;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.FileIndexFacade;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.file.impl.FileManager;
import com.intellij.psi.impl.file.impl.FileManagerImpl;
import com.intellij.psi.impl.smartPointers.SmartPointerManagerImpl;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.impl.source.tree.FileElement;
import com.intellij.psi.text.BlockSupport;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.*;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.*;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

public abstract class PsiDocumentManagerBase extends PsiDocumentManager implements DocumentListener, Disposable {
  static final Logger LOG = Logger.getInstance(PsiDocumentManagerBase.class);
  private static final Key<Document> HARD_REF_TO_DOCUMENT = Key.create("HARD_REFERENCE_TO_DOCUMENT");
  private static final Key<List<Runnable>> ACTION_AFTER_COMMIT = Key.create("ACTION_AFTER_COMMIT");

  protected final Project myProject;
  private final PsiManager myPsiManager;
  protected final DocumentCommitProcessor myDocumentCommitProcessor;
  final Set<Document> myUncommittedDocuments = ContainerUtil.newConcurrentSet();
  private final Map<Document, UncommittedInfo> myUncommittedInfos = ContainerUtil.newConcurrentMap();
  boolean myStopTrackingDocuments;
  private boolean myPerformBackgroundCommit = true;

  private volatile boolean myIsCommitInProgress;
  private static volatile boolean ourIsFullReparseInProgress;
  private final PsiToDocumentSynchronizer mySynchronizer;

  private final List<Listener> myListeners = ContainerUtil.createLockFreeCopyOnWriteList();

  protected PsiDocumentManagerBase(@NotNull Project project) {
    myProject = project;
    myPsiManager = PsiManager.getInstance(project);
    myDocumentCommitProcessor = ApplicationManager.getApplication().getService(DocumentCommitProcessor.class);
    mySynchronizer = new PsiToDocumentSynchronizer(this, project.getMessageBus());
    myPsiManager.addPsiTreeChangeListener(mySynchronizer);

    project.getMessageBus().connect().subscribe(PsiDocumentTransactionListener.TOPIC, (document, file) -> {
      myUncommittedDocuments.remove(document);
    });
  }

  @Override
  @Nullable
  public PsiFile getPsiFile(@NotNull Document document) {
    if (document instanceof DocumentWindow && !((DocumentWindow)document).isValid()) {
      return null;
    }

    PsiFile psiFile = getCachedPsiFile(document);
    if (psiFile != null) {
      return ensureValidFile(psiFile, "Cached PSI");
    }

    final VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile == null || !virtualFile.isValid()) return null;

    psiFile = getPsiFile(virtualFile);
    if (psiFile == null) return null;

    fireFileCreated(document, psiFile);

    return psiFile;
  }

  @NotNull
  private static PsiFile ensureValidFile(@NotNull PsiFile psiFile, @NotNull String debugInfo) {
    if (!psiFile.isValid()) throw new PsiInvalidElementAccessException(psiFile, debugInfo);
    return psiFile;
  }

  @Deprecated
  @ApiStatus.ScheduledForRemoval(inVersion = "2020.2")
  // todo remove when plugins come to their senses and stopped using it
  public static void cachePsi(@NotNull Document document, @Nullable PsiFile file) {
    DeprecatedMethodException.report("Unsupported method");
  }

  public void associatePsi(@NotNull Document document, @Nullable PsiFile file) {
    throw new UnsupportedOperationException();
  }

  @Override
  public PsiFile getCachedPsiFile(@NotNull Document document) {
    final VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile == null || !virtualFile.isValid()) return null;
    return getCachedPsiFile(virtualFile);
  }

  @Nullable
  FileViewProvider getCachedViewProvider(@NotNull Document document) {
    final VirtualFile virtualFile = getVirtualFile(document);
    if (virtualFile == null) return null;
    return getFileManager().findCachedViewProvider(virtualFile);
  }

  @Nullable
  private static VirtualFile getVirtualFile(@NotNull Document document) {
    final VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile == null || !virtualFile.isValid()) return null;
    return virtualFile;
  }

  @Nullable
  PsiFile getCachedPsiFile(@NotNull VirtualFile virtualFile) {
    return getFileManager().getCachedPsiFile(virtualFile);
  }

  @Nullable
  private PsiFile getPsiFile(@NotNull VirtualFile virtualFile) {
    return getFileManager().findFile(virtualFile);
  }

  @NotNull
  private FileManager getFileManager() {
    return ((PsiManagerEx)myPsiManager).getFileManager();
  }

  @Override
  public Document getDocument(@NotNull PsiFile file) {
    Document document = getCachedDocument(file);
    if (document != null) {
      if (!file.getViewProvider().isPhysical()) {
        PsiUtilCore.ensureValid(file);
        associatePsi(document, file);
      }
      return document;
    }

    FileViewProvider viewProvider = file.getViewProvider();
    if (!viewProvider.isEventSystemEnabled()) return null;

    document = FileDocumentManager.getInstance().getDocument(viewProvider.getVirtualFile());
    if (document != null) {
      if (document.getTextLength() != file.getTextLength()) {
        String message = "Document/PSI mismatch: " + file + " (" + file.getClass() + "); physical=" + viewProvider.isPhysical();
        if (document.getTextLength() + file.getTextLength() < 8096) {
          message += "\n=== document ===\n" + document.getText() + "\n=== PSI ===\n" + file.getText();
        }
        throw new AssertionError(message);
      }

      if (!viewProvider.isPhysical()) {
        PsiUtilCore.ensureValid(file);
        associatePsi(document, file);
        file.putUserData(HARD_REF_TO_DOCUMENT, document);
      }
    }

    return document;
  }

  @Override
  public Document getCachedDocument(@NotNull PsiFile file) {
    if (!file.isPhysical()) return null;
    VirtualFile vFile = file.getViewProvider().getVirtualFile();
    return FileDocumentManager.getInstance().getCachedDocument(vFile);
  }

  @Override
  public void commitAllDocuments() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    ((TransactionGuardImpl)TransactionGuard.getInstance()).assertWriteActionAllowed();

    if (myUncommittedDocuments.isEmpty()) return;

    final Document[] documents = getUncommittedDocuments();
    for (Document document : documents) {
      if (isCommitted(document)) {
        boolean success = doCommitWithoutReparse(document);
        LOG.error("Committed document in uncommitted set: " + document + ", force-committed=" + success);
      }
      else if (!doCommit(document)) {
        LOG.error("Couldn't commit " + document);
      }
    }

    assertEverythingCommitted();
  }

  @Override
  public boolean commitAllDocumentsUnderProgress() {
    Application application = ApplicationManager.getApplication();
    if (application.isWriteAccessAllowed()) {
      commitAllDocuments();
      //there are lot of existing actions/processors/tests which execute it under write lock
      //do not show this message in unit test mode
      if (!application.isUnitTestMode()) {
        LOG.error("Do not call commitAllDocumentsUnderProgress inside write-action");
      }
      return true;
    }
    final int semaphoreTimeoutInMs = 50;
    final Runnable commitAllDocumentsRunnable = () -> {
      Semaphore semaphore = new Semaphore(1);
      application.invokeLater(() -> {
        PsiDocumentManager.getInstance(myProject).performWhenAllCommitted(() -> {
          semaphore.up();
        });
      });
      while (!semaphore.waitFor(semaphoreTimeoutInMs)) {
        ProgressManager.checkCanceled();
      }
    };
    return ProgressManager.getInstance().runProcessWithProgressSynchronously(commitAllDocumentsRunnable, "Processing Documents",
                                                                             true, myProject);
  }

  private void assertEverythingCommitted() {
    LOG.assertTrue(!hasUncommitedDocuments(), myUncommittedDocuments);
  }

  @VisibleForTesting
  public boolean doCommitWithoutReparse(@NotNull Document document) {
    return finishCommitInWriteAction(document, Collections.emptyList(), Collections.emptyList(), true, true);
  }

  @Override
  public void performForCommittedDocument(@NotNull final Document doc, @NotNull final Runnable action) {
    Document document = getTopLevelDocument(doc);
    if (isCommitted(document)) {
      action.run();
    }
    else {
      addRunOnCommit(document, action);
    }
  }

  private final Map<Object, Runnable> actionsWhenAllDocumentsAreCommitted = new LinkedHashMap<>(); //accessed from EDT only
  private static final Object PERFORM_ALWAYS_KEY = ObjectUtils.sentinel("PERFORM_ALWAYS");

  /**
   * Cancel previously registered action and schedules (new) action to be executed when all documents are committed.
   *
   * @param key    the (unique) id of the action.
   * @param action The action to be executed after automatic commit.
   *               This action will overwrite any action which was registered under this key earlier.
   *               The action will be executed in EDT.
   * @return true if action has been run immediately, or false if action was scheduled for execution later.
   */
  public boolean cancelAndRunWhenAllCommitted(@NonNls @NotNull Object key, @NotNull final Runnable action) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    if (myProject.isDisposed()) {
      action.run();
      return true;
    }
    if (myUncommittedDocuments.isEmpty()) {
      if (!isCommitInProgress()) {
        // in case of fireWriteActionFinished() we didn't execute 'actionsWhenAllDocumentsAreCommitted' yet
        assert actionsWhenAllDocumentsAreCommitted.isEmpty() : actionsWhenAllDocumentsAreCommitted;
      }
      action.run();
      return true;
    }

    checkWeAreOutsideAfterCommitHandler();

    actionsWhenAllDocumentsAreCommitted.put(key, action);
    return false;
  }

  public static void addRunOnCommit(@NotNull Document document, @NotNull Runnable action) {
    synchronized (ACTION_AFTER_COMMIT) {
      List<Runnable> list = document.getUserData(ACTION_AFTER_COMMIT);
      if (list == null) {
        document.putUserData(ACTION_AFTER_COMMIT, list = new SmartList<>());
      }
      list.add(action);
    }
  }

  private static List<Runnable> getAndClearActionsAfterCommit(@NotNull Document document) {
    List<Runnable> list;
    synchronized (ACTION_AFTER_COMMIT) {
      list = document.getUserData(ACTION_AFTER_COMMIT);
      if (list != null) {
        list = new ArrayList<>(list);
        document.putUserData(ACTION_AFTER_COMMIT, null);
      }
    }
    return list;
  }

  @Override
  public void commitDocument(@NotNull final Document doc) {
    final Document document = getTopLevelDocument(doc);

    if (isEventSystemEnabled(document)) {
      ((TransactionGuardImpl)TransactionGuard.getInstance()).assertWriteActionAllowed();
    }

    if (!isCommitted(document)) {
      doCommit(document);
    }
  }

  private boolean isEventSystemEnabled(Document document) {
    FileViewProvider viewProvider = getCachedViewProvider(document);
    return viewProvider != null && viewProvider.isEventSystemEnabled() && !AbstractFileViewProvider.isFreeThreaded(viewProvider);
  }

  boolean finishCommit(@NotNull final Document document,
                       @NotNull List<? extends BooleanRunnable> finishProcessors,
                       @NotNull List<? extends BooleanRunnable> reparseInjectedProcessors,
                       final boolean synchronously,
                       @NotNull final Object reason) {
    assert !myProject.isDisposed() : "Already disposed";
    ApplicationManager.getApplication().assertIsDispatchThread();
    final boolean[] ok = {true};
    Runnable runnable = new DocumentRunnable(document, myProject) {
      @Override
      public void run() {
        ok[0] = finishCommitInWriteAction(document, finishProcessors, reparseInjectedProcessors, synchronously, false);
      }
    };
    if (synchronously) {
      runnable.run();
    }
    else {
      ApplicationManager.getApplication().runWriteAction(runnable);
    }

    if (ok[0]) {
      // run after commit actions outside write action
      runAfterCommitActions(document);
      if (DebugUtil.DO_EXPENSIVE_CHECKS && !ApplicationInfoImpl.isInStressTest()) {
        checkAllElementsValid(document, reason);
      }
    }
    return ok[0];
  }

  protected boolean finishCommitInWriteAction(@NotNull final Document document,
                                              @NotNull List<? extends BooleanRunnable> finishProcessors,
                                              @NotNull List<? extends BooleanRunnable> reparseInjectedProcessors,
                                              final boolean synchronously,
                                              boolean forceNoPsiCommit) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    if (myProject.isDisposed()) return false;
    assert !(document instanceof DocumentWindow);

    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile != null) {
      getSmartPointerManager().fastenBelts(virtualFile);
    }

    FileViewProvider viewProvider = forceNoPsiCommit ? null : getCachedViewProvider(document);

    myIsCommitInProgress = true;
    Ref<Boolean> success = new Ref<>(true);
    try {
      ProgressManager.getInstance().executeNonCancelableSection(() -> {
        if (viewProvider == null) {
          handleCommitWithoutPsi(document);
        }
        else {
          success.set(commitToExistingPsi(document, finishProcessors, reparseInjectedProcessors, synchronously, virtualFile));
        }
      });
    }
    catch (Throwable e) {
      try {
        forceReload(virtualFile, viewProvider);
      }
      finally {
        LOG.error(e);
      }
    }
    finally {
      if (success.get()) {
        myUncommittedDocuments.remove(document);
      }
      myIsCommitInProgress = false;
    }

    return success.get();
  }

  private boolean commitToExistingPsi(@NotNull Document document,
                                      @NotNull List<? extends BooleanRunnable> finishProcessors,
                                      @NotNull List<? extends BooleanRunnable> reparseInjectedProcessors,
                                      boolean synchronously,
                                      @Nullable VirtualFile virtualFile) {
    for (BooleanRunnable finishRunnable : finishProcessors) {
      boolean success = finishRunnable.run();
      if (synchronously) {
        assert success : finishRunnable + " in " + finishProcessors;
      }
      if (!success) {
        return false;
      }
    }
    clearUncommittedInfo(document);
    if (virtualFile != null) {
      getSmartPointerManager().updatePointerTargetsAfterReparse(virtualFile);
    }
    FileViewProvider viewProvider = getCachedViewProvider(document);
    if (viewProvider != null) {
      viewProvider.contentsSynchronized();
    }
    for (BooleanRunnable runnable : reparseInjectedProcessors) {
      if (!runnable.run()) return false;
    }
    return true;
  }

  void forceReload(VirtualFile virtualFile, @Nullable FileViewProvider viewProvider) {
    if (viewProvider != null) {
      ((AbstractFileViewProvider)viewProvider).markInvalidated();
    }
    if (virtualFile != null) {
      ((FileManagerImpl)getFileManager()).forceReload(virtualFile);
    }
  }

  private void checkAllElementsValid(@NotNull Document document, @NotNull final Object reason) {
    final PsiFile psiFile = getCachedPsiFile(document);
    if (psiFile != null) {
      psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
        @Override
        public void visitElement(@NotNull PsiElement element) {
          if (!element.isValid()) {
            throw new AssertionError("Commit to '" + psiFile.getVirtualFile() + "' has led to invalid element: " + element + "; Reason: '" + reason + "'");
          }
        }
      });
    }
  }

  private boolean doCommit(@NotNull final Document document) {
    assert !myIsCommitInProgress : "Do not call commitDocument() from inside PSI change listener";

    // otherwise there are many clients calling commitAllDocs() on PSI childrenChanged()
    if (getSynchronizer().isDocumentAffectedByTransactions(document)) return false;

    final PsiFile psiFile = getPsiFile(document);
    if (psiFile == null) {
      myUncommittedDocuments.remove(document);
      runAfterCommitActions(document);
      return true; // the project must be closing or file deleted
    }

    Runnable runnable = () -> {
      myIsCommitInProgress = true;
      try {
        myDocumentCommitProcessor.commitSynchronously(document, myProject, psiFile);
      }
      finally {
        myIsCommitInProgress = false;
      }
      assert !isInUncommittedSet(document) : "Document :" + document;
    };

    ApplicationManager.getApplication().runWriteAction(runnable);
    return true;
  }

  // true if the PSI is being modified and events being sent
  public boolean isCommitInProgress() {
    return myIsCommitInProgress || isFullReparseInProgress();
  }

  public static boolean isFullReparseInProgress() {
    return ourIsFullReparseInProgress;
  }

  @Override
  public <T> T commitAndRunReadAction(@NotNull final Computable<T> computation) {
    final Ref<T> ref = Ref.create(null);
    commitAndRunReadAction(() -> ref.set(computation.compute()));
    return ref.get();
  }

  @Override
  public void reparseFiles(@NotNull Collection<? extends VirtualFile> files, boolean includeOpenFiles) {
    FileContentUtilCore.reparseFiles(files);
  }

  @Override
  public void commitAndRunReadAction(@NotNull final Runnable runnable) {
    final Application application = ApplicationManager.getApplication();
    if (SwingUtilities.isEventDispatchThread()) {
      commitAllDocuments();
      runnable.run();
      return;
    }

    if (application.isReadAccessAllowed()) {
      LOG.error("Don't call commitAndRunReadAction inside ReadAction, it will cause a deadlock. "+Thread.currentThread());
    }

    while (true) {
      boolean executed = ReadAction.compute(() -> {
        if (myUncommittedDocuments.isEmpty()) {
          runnable.run();
          return true;
        }
        return false;
      });
      if (executed) break;

      ModalityState modality = ModalityState.defaultModalityState();
      Semaphore semaphore = new Semaphore(1);
      application.invokeLater(() -> {
        if (myProject.isDisposed()) {
          // committedness doesn't matter anymore; give clients a chance to do checkCanceled
          semaphore.up();
          return;
        }

        performWhenAllCommitted(() -> semaphore.up(), modality);
      }, ModalityState.any());

      while (!semaphore.waitFor(10)) {
        ProgressManager.checkCanceled();
      }
    }
  }

  /**
   * Schedules action to be executed when all documents are committed.
   *
   * @return true if action has been run immediately, or false if action was scheduled for execution later.
   */
  @Override
  public boolean performWhenAllCommitted(@NotNull final Runnable action) {
    return performWhenAllCommitted(action, ModalityState.defaultModalityState());
  }

  private boolean performWhenAllCommitted(@NotNull Runnable action, @NotNull ModalityState modality) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    checkWeAreOutsideAfterCommitHandler();

    assert !myProject.isDisposed() : "Already disposed: " + myProject;
    if (myUncommittedDocuments.isEmpty()) {
      action.run();
      return true;
    }
    CompositeRunnable actions = (CompositeRunnable)actionsWhenAllDocumentsAreCommitted.get(PERFORM_ALWAYS_KEY);
    if (actions == null) {
      actions = new CompositeRunnable();
      actionsWhenAllDocumentsAreCommitted.put(PERFORM_ALWAYS_KEY, actions);
    }
    actions.add(action);

    if (modality != ModalityState.NON_MODAL) {
      // re-add all uncommitted documents into the queue with this new modality
      // because this client obviously expects them to commit even inside modal dialog
      for (Document document : myUncommittedDocuments) {
        myDocumentCommitProcessor.commitAsynchronously(myProject, document,
                                                       "re-added because performWhenAllCommitted("+modality+") was called", modality);
      }
    }
    return false;
  }

  @Override
  public void performLaterWhenAllCommitted(@NotNull final Runnable runnable) {
    performLaterWhenAllCommitted(runnable, ModalityState.defaultModalityState());
  }

  @Override
  public void performLaterWhenAllCommitted(@NotNull final Runnable runnable, final ModalityState modalityState) {
    final Runnable whenAllCommitted = () -> ApplicationManager.getApplication().invokeLater(() -> {
      if (hasUncommitedDocuments()) {
        // no luck, will try later
        performLaterWhenAllCommitted(runnable);
      }
      else {
        runnable.run();
      }
    }, modalityState, myProject.getDisposed());
    if (ApplicationManager.getApplication().isDispatchThread() && isInsideCommitHandler()) {
      whenAllCommitted.run();
    }
    else {
      UIUtil.invokeLaterIfNeeded(() -> { if (!myProject.isDisposed()) performWhenAllCommitted(whenAllCommitted);});
    }
  }

  private static class CompositeRunnable extends ArrayList<Runnable> implements Runnable {
    @Override
    public void run() {
      for (Runnable runnable : this) {
        runnable.run();
      }
    }
  }

  private void runAfterCommitActions(@NotNull Document document) {
    if (!ApplicationManager.getApplication().isDispatchThread()) {
      // have to run in EDT to guarantee data structure safe access and "execute in EDT" callbacks contract
      ApplicationManager.getApplication().invokeLater(()-> {
        if (!myProject.isDisposed() && isCommitted(document)) runAfterCommitActions(document);
      });
      return;
    }
    ApplicationManager.getApplication().assertIsDispatchThread();
    List<Runnable> list = getAndClearActionsAfterCommit(document);
    if (list != null) {
      for (final Runnable runnable : list) {
        runnable.run();
      }
    }

    if (!hasUncommitedDocuments() && !actionsWhenAllDocumentsAreCommitted.isEmpty()) {
      List<Runnable> actions = new ArrayList<>(actionsWhenAllDocumentsAreCommitted.values());
      beforeCommitHandler();
      List<Pair<Runnable, Throwable>> exceptions = new ArrayList<>();
      try {
        for (Runnable action : actions) {
          try {
            action.run();
          }
          catch (ProcessCanceledException e) {
            // some actions are crazy enough to use PCE for their own control flow.
            // swallow and ignore to not disrupt completely unrelated control flow.
          }
          catch (Throwable e) {
            exceptions.add(Pair.create(action, e));
          }
        }
      }
      finally {
        // unblock adding listeners
        actionsWhenAllDocumentsAreCommitted.clear();
      }
      for (Pair<Runnable, Throwable> pair : exceptions) {
        Runnable action = pair.getFirst();
        Throwable e = pair.getSecond();
        LOG.error("During running " + action, e);
      }
    }
  }

  private void beforeCommitHandler() {
    actionsWhenAllDocumentsAreCommitted.put(PERFORM_ALWAYS_KEY, EmptyRunnable.getInstance()); // to prevent listeners from registering new actions during firing
  }
  private void checkWeAreOutsideAfterCommitHandler() {
    if (isInsideCommitHandler()) {
      throw new IncorrectOperationException("You must not call performWhenAllCommitted()/cancelAndRunWhenCommitted() from within after-commit handler");
    }
  }

  private boolean isInsideCommitHandler() {
    return actionsWhenAllDocumentsAreCommitted.get(PERFORM_ALWAYS_KEY) == EmptyRunnable.getInstance();
  }

  @Override
  public void addListener(@NotNull Listener listener) {
    myListeners.add(listener);
  }

  @Override
  public void removeListener(@NotNull Listener listener) {
    myListeners.remove(listener);
  }

  @Override
  public boolean isDocumentBlockedByPsi(@NotNull Document doc) {
    return false;
  }

  @Override
  public void doPostponedOperationsAndUnblockDocument(@NotNull Document doc) {
  }

  void fireDocumentCreated(@NotNull Document document, PsiFile file) {
    myProject.getMessageBus().syncPublisher(PsiDocumentListener.TOPIC).documentCreated(document, file, myProject);
    for (Listener listener : myListeners) {
      listener.documentCreated(document, file);
    }
  }

  private void fireFileCreated(@NotNull Document document, @NotNull PsiFile file) {
    myProject.getMessageBus().syncPublisher(PsiDocumentListener.TOPIC).fileCreated(file, document);
    for (Listener listener : myListeners) {
      listener.fileCreated(file, document);
    }
  }

  @Override
  @NotNull
  public CharSequence getLastCommittedText(@NotNull Document document) {
    return getLastCommittedDocument(document).getImmutableCharSequence();
  }

  @Override
  public long getLastCommittedStamp(@NotNull Document document) {
    return getLastCommittedDocument(getTopLevelDocument(document)).getModificationStamp();
  }

  @Override
  @Nullable
  public Document getLastCommittedDocument(@NotNull PsiFile file) {
    Document document = getDocument(file);
    return document == null ? null : getLastCommittedDocument(document);
  }

  @NotNull
  public DocumentEx getLastCommittedDocument(@NotNull Document document) {
    if (document instanceof FrozenDocument) return (DocumentEx)document;

    if (document instanceof DocumentWindow) {
      DocumentWindow window = (DocumentWindow)document;
      Document delegate = window.getDelegate();
      if (delegate instanceof FrozenDocument) return (DocumentEx)window;

      if (!window.isValid()) {
        throw new AssertionError("host committed: " + isCommitted(delegate) + ", window=" + window);
      }

      UncommittedInfo info = myUncommittedInfos.get(delegate);
      DocumentWindow answer = info == null ? null : info.myFrozenWindows.get(document);
      if (answer == null) answer = freezeWindow(window);
      if (info != null) answer = ConcurrencyUtil.cacheOrGet(info.myFrozenWindows, window, answer);
      return (DocumentEx)answer;
    }

    assert document instanceof DocumentImpl;
    UncommittedInfo info = myUncommittedInfos.get(document);
    return info != null ? info.myFrozen : ((DocumentImpl)document).freeze();
  }

  @NotNull
  protected DocumentWindow freezeWindow(@NotNull DocumentWindow document) {
    throw new UnsupportedOperationException();
  }

  @NotNull
  public List<DocumentEvent> getEventsSinceCommit(@NotNull Document document) {
    assert document instanceof DocumentImpl : document;
    UncommittedInfo info = myUncommittedInfos.get(document);
    if (info != null) {
      //noinspection unchecked
      return (List<DocumentEvent>)info.myEvents.clone();
    }
    return Collections.emptyList();

  }

  @Override
  @NotNull
  public Document[] getUncommittedDocuments() {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    //noinspection UnnecessaryLocalVariable
    Document[] documents = myUncommittedDocuments.toArray(Document.EMPTY_ARRAY);
    return documents; // java.util.ConcurrentHashMap.keySet().toArray() guaranteed to return array with no nulls
  }

  boolean isInUncommittedSet(@NotNull Document document) {
    return myUncommittedDocuments.contains(getTopLevelDocument(document));
  }

  @Override
  public boolean isUncommited(@NotNull Document document) {
    return !isCommitted(document);
  }

  @Override
  public boolean isCommitted(@NotNull Document document) {
    document = getTopLevelDocument(document);
    if (getSynchronizer().isInSynchronization(document)) return true;
    return (!(document instanceof DocumentEx) || !((DocumentEx)document).isInEventsHandling())
           && !isInUncommittedSet(document);
  }

  @NotNull
  private static Document getTopLevelDocument(@NotNull Document document) {
    return document instanceof DocumentWindow ? ((DocumentWindow)document).getDelegate() : document;
  }

  @Override
  public boolean hasUncommitedDocuments() {
    return !myIsCommitInProgress && !myUncommittedDocuments.isEmpty();
  }

  @Override
  public void beforeDocumentChange(@NotNull DocumentEvent event) {
    if (myStopTrackingDocuments || myProject.isDisposed()) return;

    final Document document = event.getDocument();
    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    boolean isRelevant = virtualFile != null && isRelevant(virtualFile);

    if (document instanceof DocumentImpl && !myUncommittedInfos.containsKey(document)) {
      myUncommittedInfos.put(document, new UncommittedInfo((DocumentImpl)document));
    }

    final FileViewProvider viewProvider = getCachedViewProvider(document);
    boolean inMyProject = viewProvider != null && viewProvider.getManager() == myPsiManager;
    if (!isRelevant || !inMyProject) {
      return;
    }

    final List<PsiFile> files = viewProvider.getAllFiles();
    PsiFile psiCause = null;
    for (PsiFile file : files) {
      if (file == null) {
        throw new AssertionError("View provider "+viewProvider+" ("+viewProvider.getClass()+") returned null in its files array: "+files+" for file "+viewProvider.getVirtualFile());
      }

      if (PsiToDocumentSynchronizer.isInsideAtomicChange(file)) {
        psiCause = file;
      }
    }

    if (psiCause == null) {
      beforeDocumentChangeOnUnlockedDocument(viewProvider);
    }
  }

  protected void beforeDocumentChangeOnUnlockedDocument(@NotNull final FileViewProvider viewProvider) {
  }

  @Override
  public void documentChanged(@NotNull DocumentEvent event) {
    if (myStopTrackingDocuments || myProject.isDisposed()) return;

    final Document document = event.getDocument();

    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    boolean isRelevant = virtualFile != null && isRelevant(virtualFile);

    final FileViewProvider viewProvider = getCachedViewProvider(document);
    if (viewProvider == null) {
      handleCommitWithoutPsi(document);
      return;
    }
    boolean inMyProject = viewProvider.getManager() == myPsiManager;
    if (!isRelevant || !inMyProject) {
      clearUncommittedInfo(document);
      return;
    }

    List<PsiFile> files = viewProvider.getAllFiles();
    if (files.isEmpty()) {
      handleCommitWithoutPsi(document);
      return;
    }

    boolean commitNecessary = files.stream().noneMatch(file -> PsiToDocumentSynchronizer.isInsideAtomicChange(file) || !(file instanceof PsiFileImpl));

    boolean forceCommit = ApplicationManager.getApplication().hasWriteAction(ExternalChangeAction.class) &&
                          (SystemProperties.getBooleanProperty("idea.force.commit.on.external.change", false) ||
                           ApplicationManager.getApplication().isHeadlessEnvironment() && !ApplicationManager.getApplication().isUnitTestMode());

    // Consider that it's worth to perform complete re-parse instead of merge if the whole document text is replaced and
    // current document lines number is roughly above 5000. This makes sense in situations when external change is performed
    // for the huge file (that causes the whole document to be reloaded and 'merge' way takes a while to complete).
    if (event.isWholeTextReplaced() && document.getTextLength() > 100000) {
      document.putUserData(BlockSupport.DO_NOT_REPARSE_INCREMENTALLY, Boolean.TRUE);
    }

    if (commitNecessary) {
      assert !(document instanceof DocumentWindow);
      myUncommittedDocuments.add(document);
      if (forceCommit) {
        commitDocument(document);
      }
      else if (!document.isInBulkUpdate() && myPerformBackgroundCommit) {
        myDocumentCommitProcessor.commitAsynchronously(myProject, document, event, ModalityState.defaultModalityState());
      }
    }
    else {
      clearUncommittedInfo(document);
    }
  }

  @Override
  public void bulkUpdateStarting(@NotNull Document document) {
    document.putUserData(BlockSupport.DO_NOT_REPARSE_INCREMENTALLY, Boolean.TRUE);
  }

  @Override
  public void bulkUpdateFinished(@NotNull Document document) {
    myDocumentCommitProcessor.commitAsynchronously(myProject, document, "Bulk update finished",
                                                   ModalityState.defaultModalityState());
  }

  @ApiStatus.Internal
  public class PriorityEventCollector implements PrioritizedInternalDocumentListener {
    @Override
    public int getPriority() {
      return EditorDocumentPriorities.RANGE_MARKER;
    }

    @Override
    public void moveTextHappened(@NotNull Document document, int start, int end, int base) {
      UncommittedInfo info = myUncommittedInfos.get(document);
      if (info != null) {
        info.myEvents.add(new RetargetRangeMarkers(document, start, end, base));
      }
    }

    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
      UncommittedInfo info = myUncommittedInfos.get(event.getDocument());
      if (info != null) {
        info.myEvents.add(event);
      }
    }
  }

  void handleCommitWithoutPsi(@NotNull Document document) {
    final UncommittedInfo prevInfo = clearUncommittedInfo(document);
    if (prevInfo == null) {
      return;
    }

    myUncommittedDocuments.remove(document);

    if (!myProject.isInitialized() || myProject.isDisposed() || myProject.isDefault()) {
      return;
    }

    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile != null) {
      FileManager fileManager = getFileManager();
      FileViewProvider viewProvider = fileManager.findCachedViewProvider(virtualFile);
      if (viewProvider != null) {
        // we can end up outside write action here if the document has forUseInNonAWTThread=true
        ApplicationManager.getApplication().runWriteAction((ExternalChangeAction)() ->
          ((AbstractFileViewProvider)viewProvider).onContentReload());
      } else if (FileIndexFacade.getInstance(myProject).isInContent(virtualFile)) {
        ApplicationManager.getApplication().runWriteAction((ExternalChangeAction)() ->
          ((FileManagerImpl)fileManager).firePropertyChangedForUnloadedPsi());
      }
    }

    runAfterCommitActions(document);
  }

  @Nullable
  private UncommittedInfo clearUncommittedInfo(@NotNull Document document) {
    UncommittedInfo info = myUncommittedInfos.remove(document);
    if (info != null) {
      getSmartPointerManager().updatePointers(document, info.myFrozen, info.myEvents);
    }
    return info;
  }

  private SmartPointerManagerImpl getSmartPointerManager() {
    return (SmartPointerManagerImpl)SmartPointerManager.getInstance(myProject);
  }

  private boolean isRelevant(@NotNull VirtualFile virtualFile) {
    return !myProject.isDisposed() && !virtualFile.getFileType().isBinary();
  }

  public static boolean checkConsistency(@NotNull PsiFile psiFile, @NotNull Document document) {
    //todo hack
    if (psiFile.getVirtualFile() == null) return true;

    CharSequence editorText = document.getCharsSequence();
    int documentLength = document.getTextLength();
    if (psiFile.textMatches(editorText)) {
      LOG.assertTrue(psiFile.getTextLength() == documentLength);
      return true;
    }

    char[] fileText = psiFile.textToCharArray();
    @SuppressWarnings("NonConstantStringShouldBeStringBuffer")
    @NonNls String error = "File '" + psiFile.getName() + "' text mismatch after reparse. " +
                           "File length=" + fileText.length + "; Doc length=" + documentLength + "\n";
    int i = 0;
    for (; i < documentLength; i++) {
      if (i >= fileText.length) {
        error += "editorText.length > psiText.length i=" + i + "\n";
        break;
      }
      if (i >= editorText.length()) {
        error += "editorText.length > psiText.length i=" + i + "\n";
        break;
      }
      if (editorText.charAt(i) != fileText[i]) {
        error += "first unequal char i=" + i + "\n";
        break;
      }
    }
    //error += "*********************************************" + "\n";
    //if (i <= 500){
    //  error += "Equal part:" + editorText.subSequence(0, i) + "\n";
    //}
    //else{
    //  error += "Equal part start:\n" + editorText.subSequence(0, 200) + "\n";
    //  error += "................................................" + "\n";
    //  error += "................................................" + "\n";
    //  error += "................................................" + "\n";
    //  error += "Equal part end:\n" + editorText.subSequence(i - 200, i) + "\n";
    //}
    error += "*********************************************" + "\n";
    error += "Editor Text tail:(" + (documentLength - i) + ")\n";// + editorText.subSequence(i, Math.min(i + 300, documentLength)) + "\n";
    error += "*********************************************" + "\n";
    error += "Psi Text tail:(" + (fileText.length - i) + ")\n";
    error += "*********************************************" + "\n";

    if (document instanceof DocumentWindow) {
      error += "doc: '" + document.getText() + "'\n";
      error += "psi: '" + psiFile.getText() + "'\n";
      error += "ast: '" + psiFile.getNode().getText() + "'\n";
      error += psiFile.getLanguage() + "\n";
      PsiElement context = InjectedLanguageManager.getInstance(psiFile.getProject()).getInjectionHost(psiFile);
      if (context != null) {
        error += "context: " + context + "; text: '" + context.getText() + "'\n";
        error += "context file: " + context.getContainingFile() + "\n";
      }
      error += "document window ranges: " + Arrays.asList(((DocumentWindow)document).getHostRanges()) + "\n";
    }
    LOG.error(error);
    //document.replaceString(0, documentLength, psiFile.getText());
    return false;
  }

  @TestOnly
  public void clearUncommittedDocuments() {
    myUncommittedInfos.clear();
    myUncommittedDocuments.clear();
    mySynchronizer.cleanupForNextTest();
  }

  @TestOnly
  public void disableBackgroundCommit(@NotNull Disposable parentDisposable) {
    assert myPerformBackgroundCommit;
    myPerformBackgroundCommit = false;
    Disposer.register(parentDisposable, () -> myPerformBackgroundCommit = true);
  }

  @Override
  public void dispose() {}

  @NotNull
  public PsiToDocumentSynchronizer getSynchronizer() {
    return mySynchronizer;
  }

  @SuppressWarnings("AssignmentToStaticFieldFromInstanceMethod")
  public void reparseFileFromText(@NotNull PsiFileImpl file) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    if (isCommitInProgress()) throw new IllegalStateException("Re-entrant commit is not allowed");

    FileElement node = file.calcTreeElement();
    CharSequence text = node.getChars();
    ourIsFullReparseInProgress = true;
    try {
      WriteAction.run(() -> {
        ProgressIndicator indicator = ProgressIndicatorProvider.getGlobalProgressIndicator();
        if (indicator == null) indicator = new EmptyProgressIndicator();
        DiffLog log = BlockSupportImpl.makeFullParse(file, node, text, indicator, text).log;
        log.doActualPsiChange(file);
        file.getViewProvider().contentsSynchronized();
      });
    }
    finally {
      ourIsFullReparseInProgress = false;
    }
  }

  private static class UncommittedInfo {
    private final FrozenDocument myFrozen;
    private final ArrayList<DocumentEvent> myEvents = new ArrayList<>();
    private final ConcurrentMap<DocumentWindow, DocumentWindow> myFrozenWindows = ContainerUtil.newConcurrentMap();

    private UncommittedInfo(@NotNull DocumentImpl original) {
      myFrozen = original.freeze();
    }
  }

  @NotNull
  List<BooleanRunnable> reparseChangedInjectedFragments(@NotNull Document hostDocument,
                                                        @NotNull PsiFile hostPsiFile,
                                                        @NotNull TextRange range,
                                                        @NotNull ProgressIndicator indicator,
                                                        @NotNull ASTNode oldRoot,
                                                        @NotNull ASTNode newRoot) {
    return Collections.emptyList();
  }

  @TestOnly
  public boolean isDefaultProject() {
    return myProject.isDefault();
  }
}
