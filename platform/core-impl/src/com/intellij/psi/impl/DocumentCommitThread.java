// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.diagnostic.PluginException;
import com.intellij.lang.FileASTNode;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.progress.util.StandardProgressIndicatorBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.FileElement;
import com.intellij.psi.text.BlockSupport;
import com.intellij.util.SmartList;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.BoundedTaskExecutor;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.ide.PooledThreadExecutor;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class DocumentCommitThread implements Disposable, DocumentCommitProcessor {
  private static final Logger LOG = Logger.getInstance(DocumentCommitThread.class);
  private static final String SYNC_COMMIT_REASON = "Sync commit";

  private final ExecutorService executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Document Committing Pool", PooledThreadExecutor.INSTANCE, 1, this);
  private volatile boolean isDisposed;

  static DocumentCommitThread getInstance() {
    return (DocumentCommitThread)ServiceManager.getService(DocumentCommitProcessor.class);
  }

  DocumentCommitThread() {
  }

  @Override
  public void dispose() {
    isDisposed = true;
  }

  @Override
  public void commitAsynchronously(@NotNull final Project project,
                                   @NotNull final Document document,
                                   @NonNls @NotNull Object reason,
                                   @NotNull ModalityState modality) {
    assert !isDisposed : "already disposed";

    if (!project.isInitialized()) return;
    PsiDocumentManagerBase documentManager = (PsiDocumentManagerBase)PsiDocumentManager.getInstance(project);
    PsiFile psiFile = documentManager.getCachedPsiFile(document);
    if (psiFile == null || psiFile instanceof PsiCompiledElement) return;

    CommitTask task =
      new CommitTask(project, document, reason, modality, documentManager.getLastCommittedText(document));
    ReadAction
      .nonBlocking(() -> commitUnderProgress(task, false))
      .expireWhen(() -> project.isDisposed() || isDisposed || !documentManager.isInUncommittedSet(document) || !task.isStillValid())
      .coalesceBy(task)
      .finishOnUiThread(modality, edtFinish -> {
        if (edtFinish != null) {
          edtFinish.run();
        }
        else {
          commitAsynchronously(project, document, "No edtFinish, re-added", modality);
        }
      })
      .submit(executor);
  }

  @SuppressWarnings("unused")
  private void log(Project project, @NonNls String msg, @Nullable CommitTask task, @NonNls Object... args) {
    //System.out.println(msg + "; task: "+task + "; args: "+StringUtil.first(java.util.Arrays.toString(args), 80, true));
  }

  @Override
  public void commitSynchronously(@NotNull Document document, @NotNull Project project, @NotNull PsiFile psiFile) {
    assert !isDisposed;

    if (!project.isInitialized() && !project.isDefault()) {
      @NonNls String s = project + "; Disposed: "+project.isDisposed()+"; Open: "+project.isOpen();
      try {
        Disposer.dispose(project);
      }
      catch (Throwable ignored) {
        // do not fill log with endless exceptions
      }
      throw new RuntimeException(s);
    }

    Lock documentLock = getDocumentLock(document);

    CommitTask task = new CommitTask(project, document, SYNC_COMMIT_REASON, ModalityState.defaultModalityState(),
                                     PsiDocumentManager.getInstance(project).getLastCommittedText(document));

    documentLock.lock();
    try {
      Runnable finish = commitUnderProgress(task, true);
      assert finish != null;
      finish.run();
    }
    finally {
      documentLock.unlock();
    }
  }

  // returns finish commit Runnable (to be invoked later in EDT) or null on failure
  @Nullable
  private Runnable commitUnderProgress(@NotNull CommitTask task, boolean synchronously) {
    final Document document = task.getDocument();
    final Project project = task.project;
    final PsiDocumentManagerBase documentManager = (PsiDocumentManagerBase)PsiDocumentManager.getInstance(project);
    final List<BooleanRunnable> finishProcessors = new SmartList<>();
    List<BooleanRunnable> reparseInjectedProcessors = new SmartList<>();

    Lock lock = getDocumentLock(document);
    if (!lock.tryLock()) {
      return null;
    }
    try {
      FileViewProvider viewProvider = documentManager.getCachedViewProvider(document);
      if (viewProvider == null) {
        finishProcessors.add(handleCommitWithoutPsi(documentManager, task));
      } else {
        for (PsiFile file : viewProvider.getAllFiles()) {
          FileASTNode oldFileNode = file.getNode();
          ProperTextRange changedPsiRange = ChangedPsiRangeUtil
            .getChangedPsiRange(file, task.document, task.myLastCommittedText, document.getImmutableCharSequence());
          if (changedPsiRange != null) {
            BooleanRunnable finishProcessor = doCommit(task, file, oldFileNode, changedPsiRange, reparseInjectedProcessors);
            finishProcessors.add(finishProcessor);
          }
        }
      }
    }
    finally {
      lock.unlock();
    }

    return createFinishCommitInEDTRunnable(task, synchronously, finishProcessors, reparseInjectedProcessors);
  }

  @NotNull
  private Runnable createFinishCommitInEDTRunnable(@NotNull final CommitTask task,
                                                   final boolean synchronously,
                                                   @NotNull List<? extends BooleanRunnable> finishProcessors,
                                                   @NotNull List<? extends BooleanRunnable> reparseInjectedProcessors) {
    return () -> {
      ApplicationManager.getApplication().assertIsDispatchThread();
      Document document = task.getDocument();
      Project project = task.project;
      if (project.isDisposed()) {
        return;
      }
      PsiDocumentManagerBase documentManager = (PsiDocumentManagerBase)PsiDocumentManager.getInstance(project);
      boolean success = documentManager.finishCommit(document, finishProcessors, reparseInjectedProcessors,
                                                                         synchronously, task.reason);
      if (synchronously) {
        assert success;
      }
      if (synchronously || success) {
        assert !documentManager.isInUncommittedSet(document);
      }
      if (success) {
        log(project, "Commit finished", task);
      }
      else {
        // add document back to the queue
        commitAsynchronously(project, document, "Re-added back", task.myCreationModality);
      }
    };
  }

  @NotNull
  private BooleanRunnable handleCommitWithoutPsi(@NotNull final PsiDocumentManagerBase documentManager,
                                                     @NotNull final CommitTask task) {
    return () -> {
      log(task.project, "Finishing without PSI", task);
      Document document = task.getDocument();
      if (!task.isStillValid() || documentManager.getCachedViewProvider(document) != null) {
        return false;
      }

      documentManager.handleCommitWithoutPsi(document);
      return true;
    };
  }

  @Override
  public String toString() {
    return "Document commit thread; application: "+ApplicationManager.getApplication()+"; isDisposed: "+isDisposed;
  }

  @TestOnly
  @VisibleForTesting
  // NB: failures applying EDT tasks are not handled - i.e. failed documents are added back to the queue and the method returns
  public void waitForAllCommits(long timeout, @NotNull TimeUnit timeUnit) throws ExecutionException, InterruptedException, TimeoutException {
    ApplicationManager.getApplication().assertIsDispatchThread();
    assert !ApplicationManager.getApplication().isWriteAccessAllowed();

    UIUtil.dispatchAllInvocationEvents();
    while (!((BoundedTaskExecutor)executor).isEmpty()) {
      ((BoundedTaskExecutor)executor).waitAllTasksExecuted(timeout, timeUnit);
      UIUtil.dispatchAllInvocationEvents();
    }
  }

  private static class CommitTask {
    @NotNull private final Document document;
    @NotNull final Project project;
    private final int modificationSequence; // store initial document modification sequence here to check if it changed later before commit in EDT

    @NotNull final Object reason;
    @NotNull final ModalityState myCreationModality;
    private final CharSequence myLastCommittedText;

    CommitTask(@NotNull final Project project,
               @NotNull final Document document,
               @NotNull Object reason,
               @NotNull ModalityState modality,
               @NotNull CharSequence lastCommittedText) {
      this.document = document;
      this.project = project;
      this.reason = reason;
      myCreationModality = modality;
      myLastCommittedText = lastCommittedText;
      modificationSequence = ((DocumentEx)document).getModificationSequence();
    }

    @NonNls
    @Override
    public String toString() {
      Document document = getDocument();
      String reasonInfo = " task reason: " + StringUtil.first(String.valueOf(reason), 180, true) +
                          (isStillValid() ? "" : "; changed: old seq=" + modificationSequence + ", new seq=" + ((DocumentEx)document).getModificationSequence());
      String contextInfo = " modality: " + myCreationModality;
      return System.identityHashCode(this)+"; " + contextInfo + reasonInfo;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof CommitTask)) return false;

      CommitTask task = (CommitTask)o;

      return Comparing.equal(getDocument(),task.getDocument()) && project.equals(task.project);
    }

    @Override
    public int hashCode() {
      int result = getDocument().hashCode();
      result = 31 * result + project.hashCode();
      return result;
    }

    boolean isStillValid() {
      Document document = getDocument();
      return ((DocumentEx)document).getModificationSequence() == modificationSequence;
    }

    @NotNull
    Document getDocument() {
      return document;
    }

  }

  // returns runnable to execute under write action in AWT to finish the commit, updates "outChangedRange"
  @NotNull
  private static BooleanRunnable doCommit(@NotNull final CommitTask task,
                                          @NotNull final PsiFile file,
                                          @NotNull final FileASTNode oldFileNode,
                                          @NotNull ProperTextRange changedPsiRange,
                                          @NotNull List<? super BooleanRunnable> outReparseInjectedProcessors) {
    Document document = task.getDocument();
    final CharSequence newDocumentText = document.getImmutableCharSequence();

    final Boolean data = document.getUserData(BlockSupport.DO_NOT_REPARSE_INCREMENTALLY);
    if (data != null) {
      document.putUserData(BlockSupport.DO_NOT_REPARSE_INCREMENTALLY, null);
      file.putUserData(BlockSupport.DO_NOT_REPARSE_INCREMENTALLY, data);
    }

    PsiDocumentManagerBase documentManager = (PsiDocumentManagerBase)PsiDocumentManager.getInstance(task.project);

    DiffLog diffLog;
    ProgressIndicator indicator = ProgressIndicatorProvider.getGlobalProgressIndicator();
    if (indicator == null) indicator = new EmptyProgressIndicator();
    try (
      BlockSupportImpl.ReparseResult result =
        BlockSupportImpl.reparse(file, oldFileNode, changedPsiRange, newDocumentText, indicator, task.myLastCommittedText)) {
      diffLog = result.log;


      List<BooleanRunnable> injectedRunnables =
        documentManager.reparseChangedInjectedFragments(document, file, changedPsiRange, indicator, result.oldRoot, result.newRoot);
      outReparseInjectedProcessors.addAll(injectedRunnables);
    }
    catch (ProcessCanceledException e) {
      throw e;
    }
    catch (Throwable e) {
      LOG.error(e);
      return () -> {
        documentManager.forceReload(file.getViewProvider().getVirtualFile(), file.getViewProvider());
        return true;
      };
    }

    return () -> {
      FileViewProvider viewProvider = file.getViewProvider();
      Document document1 = task.getDocument();
      if (!task.isStillValid() ||
          ((PsiDocumentManagerBase)PsiDocumentManager.getInstance(file.getProject())).getCachedViewProvider(document1) != viewProvider) {
        return false; // optimistic locking failed
      }

      if (!ApplicationManager.getApplication().isWriteAccessAllowed()) {
        VirtualFile vFile = viewProvider.getVirtualFile();
        LOG.error("Write action expected" +
                  "; document=" + document1 +
                  "; file=" + file + " of " + file.getClass() +
                  "; file.valid=" + file.isValid() +
                  "; file.eventSystemEnabled=" + viewProvider.isEventSystemEnabled() +
                  "; viewProvider=" + viewProvider + " of " + viewProvider.getClass() +
                  "; language=" + file.getLanguage() +
                  "; vFile=" + vFile + " of " + vFile.getClass() +
                  "; free-threaded=" + AbstractFileViewProvider.isFreeThreaded(viewProvider));
      }

      diffLog.doActualPsiChange(file);

      assertAfterCommit(document1, file, (FileElement)oldFileNode);

      return true;
    };
  }

  private static void assertAfterCommit(@NotNull Document document, @NotNull final PsiFile file, @NotNull FileElement oldFileNode) {
    if (oldFileNode.getTextLength() != document.getTextLength()) {
      final String documentText = document.getText();
      String fileText = file.getText();
      boolean sameText = Comparing.equal(fileText, documentText);
      String errorMessage = "commitDocument() left PSI inconsistent: " + DebugUtil.diagnosePsiDocumentInconsistency(file, document) +
                            "; node.length=" + oldFileNode.getTextLength() +
                            "; doc.text" + (sameText ? "==" : "!=") + "file.text" +
                            "; file name:" + file.getName() +
                            "; type:" + file.getFileType() +
                            "; lang:" + file.getLanguage();
      PluginException.logPluginError(LOG, errorMessage, null, file.getLanguage().getClass());

      file.putUserData(BlockSupport.DO_NOT_REPARSE_INCREMENTALLY, Boolean.TRUE);
      try {
        BlockSupport blockSupport = BlockSupport.getInstance(file.getProject());
        final DiffLog diffLog = blockSupport.reparseRange(file, file.getNode(), new TextRange(0, documentText.length()), documentText,
                                                          new StandardProgressIndicatorBase(),
                                                          oldFileNode.getText());
        diffLog.doActualPsiChange(file);

        if (oldFileNode.getTextLength() != document.getTextLength()) {
          PluginException.logPluginError(LOG, "PSI is broken beyond repair in: " + file, null, file.getLanguage().getClass());
        }
      }
      finally {
        file.putUserData(BlockSupport.DO_NOT_REPARSE_INCREMENTALLY, null);
      }
    }
  }

  /**
   * @return an internal lock object to prevent read & write phases of commit from running simultaneously for free-threaded PSI
   */
  private static Lock getDocumentLock(Document document) {
    Lock lock = document.getUserData(DOCUMENT_LOCK);
    return lock != null ? lock : ((UserDataHolderEx)document).putUserDataIfAbsent(DOCUMENT_LOCK, new ReentrantLock());
  }
  private static final Key<Lock> DOCUMENT_LOCK = Key.create("DOCUMENT_LOCK");

}
