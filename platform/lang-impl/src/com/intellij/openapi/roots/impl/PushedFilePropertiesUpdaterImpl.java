// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.roots.impl;

import com.intellij.ProjectTopics;
import com.intellij.diagnostic.PluginException;
import com.intellij.openapi.application.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointListener;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.fileTypes.InternalFileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressWrapper;
import com.intellij.openapi.project.*;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.ClearableLazyValue;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.impl.file.impl.FileManagerImpl;
import com.intellij.ui.GuiUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexProjectHandler;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;

public final class PushedFilePropertiesUpdaterImpl extends PushedFilePropertiesUpdater {
  private static final Logger LOG = Logger.getInstance(PushedFilePropertiesUpdater.class);

  private final Project myProject;

  private final ClearableLazyValue<List<FilePropertyPusher<?>>> myFilePushers = ClearableLazyValue.create(() -> {
    //noinspection CodeBlock2Expr
    return ContainerUtil.findAll(FilePropertyPusher.EP_NAME.getExtensionList(), pusher -> !pusher.pushDirectoriesOnly());
  });

  private final Queue<Runnable> myTasks = new ConcurrentLinkedQueue<>();

  public PushedFilePropertiesUpdaterImpl(@NotNull Project project) {
    myProject = project;

    project.getMessageBus().connect().subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
      @Override
      public void rootsChanged(@NotNull ModuleRootEvent event) {
        if (LOG.isTraceEnabled()) {
          LOG.trace(new Throwable("Processing roots changed event (caused by file type change: " + event.isCausedByFileTypesChange() + ")"));
        }
        for (FilePropertyPusher<?> pusher : FilePropertyPusher.EP_NAME.getExtensionList()) {
          pusher.afterRootsChanged(project);
        }
      }
    });
    FilePropertyPusher.EP_NAME.addExtensionPointListener(new ExtensionPointListener<FilePropertyPusher<?>>() {
      @Override
      public void extensionAdded(@NotNull FilePropertyPusher<?> pusher, @NotNull PluginDescriptor pluginDescriptor) {
        queueFullUpdate();
      }

      @Override
      public void extensionRemoved(@NotNull FilePropertyPusher<?> extension, @NotNull PluginDescriptor pluginDescriptor) {
        myFilePushers.drop();
      }
    }, project);
  }

  private void queueFullUpdate() {
    myFilePushers.drop();
    myTasks.clear();
    queueTasks(Arrays.asList(this::initializeProperties, () -> doPushAll(FilePropertyPusher.EP_NAME.getExtensionList())));
  }

  @ApiStatus.Internal
  public void processAfterVfsChanges(@NotNull List<? extends VFileEvent> events) {
    List<Runnable> syncTasks = new ArrayList<>();
    List<Runnable> delayedTasks = new ArrayList<>();
    for (VFileEvent event : events) {
      if (event instanceof VFileCreateEvent) {
        boolean isDirectory = ((VFileCreateEvent)event).isDirectory();
        List<FilePropertyPusher<?>> pushers = isDirectory ? FilePropertyPusher.EP_NAME.getExtensionList() : myFilePushers.getValue();

        if (!event.isFromRefresh()) {
          ContainerUtil.addIfNotNull(syncTasks, createRecursivePushTask(event, pushers));
        }
        else {
          FileType fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(((VFileCreateEvent)event).getChildName());
          boolean isProjectOrWorkspaceFile = fileType instanceof InternalFileType ||
                      VfsUtilCore.findContainingDirectory(((VFileCreateEvent)event).getParent(), Project.DIRECTORY_STORE_FOLDER) != null;
          if (!isProjectOrWorkspaceFile) {
            ContainerUtil.addIfNotNull(delayedTasks, createRecursivePushTask(event, pushers));
          }
        }
      }
      else if (event instanceof VFileMoveEvent || event instanceof VFileCopyEvent) {
        VirtualFile file = getFile(event);
        if (file == null) continue;
        boolean isDirectory = file.isDirectory();
        List<FilePropertyPusher<?>> pushers = isDirectory ? FilePropertyPusher.EP_NAME.getExtensionList() : myFilePushers.getValue();
        for (FilePropertyPusher<?> pusher : pushers) {
          file.putUserData(pusher.getFileDataKey(), null);
        }
        ContainerUtil.addIfNotNull(syncTasks, createRecursivePushTask(event, pushers));
      }
    }
    boolean pushingSomethingSynchronously = !syncTasks.isEmpty() && syncTasks.size() < FileBasedIndexProjectHandler.ourMinFilesToStartDumMode;
    if (pushingSomethingSynchronously) {
      // push synchronously to avoid entering dumb mode in the middle of a meaningful write action
      // when only a few files are created/moved
      syncTasks.forEach(Runnable::run);
    }
    else {
      delayedTasks.addAll(syncTasks);
    }
    if (!delayedTasks.isEmpty()) {
      queueTasks(delayedTasks);
    }
    if (pushingSomethingSynchronously) {
      GuiUtils.invokeLaterIfNeeded(() -> scheduleDumbModeReindexingIfNeeded(), ModalityState.defaultModalityState());
    }
  }

  private static VirtualFile getFile(@NotNull VFileEvent event) {
    VirtualFile file = event.getFile();
    if (event instanceof VFileCopyEvent) {
      file = ((VFileCopyEvent)event).getNewParent().findChild(((VFileCopyEvent)event).getNewChildName());
    }
    return file;
  }

  @Override
  public void runConcurrentlyIfPossible(List<Runnable> tasks) {
      invokeConcurrentlyIfPossible(tasks);
  }

  @Override
  public void initializeProperties() {
    for (FilePropertyPusher<?> pusher : FilePropertyPusher.EP_NAME.getExtensionList()) {
      pusher.initExtra(myProject, myProject.getMessageBus(), new FilePropertyPusher.Engine() {
        @Override
        public void pushAll() {
          PushedFilePropertiesUpdaterImpl.this.pushAll(pusher);
        }

        @Override
        public void pushRecursively(@NotNull VirtualFile file, @NotNull Project project) {
          queueTasks(ContainerUtil.createMaybeSingletonList(createRecursivePushTask(new VFileContentChangeEvent(this, file, 0,0,false), Collections.singletonList(pusher))));
        }
      });
    }
  }

  @Override
  public void pushAllPropertiesNow() {
    performPushTasks();
    doPushAll(FilePropertyPusher.EP_NAME.getExtensionList());
  }

  @Nullable
  private Runnable createRecursivePushTask(@NotNull VFileEvent event, @NotNull List<? extends FilePropertyPusher<?>> pushers) {
    if (pushers.isEmpty()) {
      return null;
    }

    return () -> {
      // delay calling event.getFile() until background to avoid expensive VFileCreateEvent.getFile() in EDT
      VirtualFile dir = getFile(event);
      final ProjectFileIndex fileIndex = ProjectRootManager.getInstance(myProject).getFileIndex();
      if (dir != null && ReadAction.compute(() -> fileIndex.isInContent(dir)) && !ProjectUtil.isProjectOrWorkspaceFile(dir)) {
        doPushRecursively(dir, pushers, fileIndex);
      }
    };
  }

  private void doPushRecursively(VirtualFile dir, @NotNull List<? extends FilePropertyPusher<?>> pushers, ProjectFileIndex fileIndex) {
    fileIndex.iterateContentUnderDirectory(dir, fileOrDir -> {
      applyPushersToFile(fileOrDir, pushers, null);
      return true;
    });
  }

  private void queueTasks(@NotNull List<? extends Runnable> actions) {
    actions.forEach(myTasks::offer);
    DumbModeTask task = new DumbModeTask(this) {
      @Override
      public void performInDumbMode(@NotNull ProgressIndicator indicator) {
        performPushTasks();
      }
    };
    myProject.getMessageBus().connect(task).subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
      @Override
      public void rootsChanged(@NotNull ModuleRootEvent event) {
        DumbService.getInstance(myProject).cancelTask(task);
      }
    });
    FilePropertyPusher.EP_NAME.addExtensionPointListener(new ExtensionPointListener<FilePropertyPusher<?>>() {
      @Override
      public void extensionAdded(@NotNull FilePropertyPusher<?> pusher, @NotNull PluginDescriptor pluginDescriptor) {
        DumbService.getInstance(myProject).cancelTask(task);
        queueFullUpdate();
      }

      @Override
      public void extensionRemoved(@NotNull FilePropertyPusher<?> pusher, @NotNull PluginDescriptor pluginDescriptor) {
        DumbService.getInstance(myProject).cancelTask(task);
        queueFullUpdate();
      }
    }, task);
    DumbService.getInstance(myProject).queueTask(task);
  }

  private void performPushTasks() {
    boolean hadTasks = false;
    while (true) {
      Runnable task = myTasks.poll();
      if (task == null) {
        break;
      }
      try {
        task.run();
        hadTasks = true;
      }
      catch (ProcessCanceledException e) {
        queueTasks(Collections.singletonList(task)); // reschedule dumb mode and ensure the canceled task is enqueued again
        throw e;
      }
    }

    if (hadTasks) {
      scheduleDumbModeReindexingIfNeeded();
    }
  }

  private void scheduleDumbModeReindexingIfNeeded() {
    if (myProject.isDisposed()) return;

    DumbModeTask task = FileBasedIndexProjectHandler.createChangedFilesIndexingTask(myProject);
    if (task != null) {
      DumbService.getInstance(myProject).queueTask(task);
    }
  }

  @Override
  public void filePropertiesChanged(@NotNull VirtualFile fileOrDir, @NotNull Condition<? super VirtualFile> acceptFileCondition) {
    if (fileOrDir.isDirectory()) {
      for (VirtualFile child : fileOrDir.getChildren()) {
        if (!child.isDirectory() && acceptFileCondition.value(child)) {
          filePropertiesChanged(child);
        }
      }
    }
    else if (acceptFileCondition.value(fileOrDir)) {
      filePropertiesChanged(fileOrDir);
    }
  }

  private static <T> T findPusherValuesUpwards(Project project, VirtualFile dir, FilePropertyPusher<? extends T> pusher, T moduleValue) {
    final T value = pusher.getImmediateValue(project, dir);
    if (value != null) return value;
    if (moduleValue != null) return moduleValue;
    return findPusherValuesFromParent(project, dir, pusher);
  }

  private static <T> T findPusherValuesUpwards(Project project, VirtualFile dir, FilePropertyPusher<? extends T> pusher) {
    final T userValue = dir.getUserData(pusher.getFileDataKey());
    if (userValue != null) return userValue;
    final T value = pusher.getImmediateValue(project, dir);
    if (value != null) return value;
    return findPusherValuesFromParent(project, dir, pusher);
  }

  private static <T> T findPusherValuesFromParent(Project project, VirtualFile dir, FilePropertyPusher<? extends T> pusher) {
    final VirtualFile parent = dir.getParent();
    if (parent != null && ProjectFileIndex.getInstance(project).isInContent(parent)) return findPusherValuesUpwards(project, parent, pusher);
    T projectValue = pusher.getImmediateValue(project, null);
    return projectValue != null ? projectValue : pusher.getDefaultValue();
  }

  @Override
  public void pushAll(@NotNull FilePropertyPusher<?>... pushers) {
    queueTasks(Collections.singletonList(() -> doPushAll(Arrays.asList(pushers))));
  }

  private void doPushAll(@NotNull List<? extends FilePropertyPusher<?>> pushers) {
    Module[] modules = ReadAction.compute(() -> ModuleManager.getInstance(myProject).getModules());

    List<Runnable> tasks = new ArrayList<>();

    for (final Module module : modules) {
      Runnable iteration = ReadAction.compute(() -> {
        if (module.isDisposed()) return EmptyRunnable.INSTANCE;
        ProgressManager.checkCanceled();

        final Object[] moduleValues = new Object[pushers.size()];
        for (int i = 0; i < moduleValues.length; i++) {
          moduleValues[i] = pushers.get(i).getImmediateValue(module);
        }

        final ModuleFileIndex fileIndex = ModuleRootManager.getInstance(module).getFileIndex();
        return () -> fileIndex.iterateContent(fileOrDir -> {
          applyPushersToFile(fileOrDir, pushers, moduleValues);
          return true;
        });
      });
      tasks.add(iteration);
    }

    invokeConcurrentlyIfPossible(tasks);
  }

  public static void invokeConcurrentlyIfPossible(final List<? extends Runnable> tasks) {
    if (tasks.size() == 1 ||
        ApplicationManager.getApplication().isWriteAccessAllowed()) {
      for(Runnable r:tasks) r.run();
      return;
    }

    final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();

    final ConcurrentLinkedQueue<Runnable> tasksQueue = new ConcurrentLinkedQueue<>(tasks);
    List<Future<?>> results = new ArrayList<>();
    if (tasks.size() > 1) {
      int numThreads = Math.max(Math.min(CacheUpdateRunner.indexingThreadCount() - 1, tasks.size() - 1), 1);

      for (int i = 0; i < numThreads; ++i) {
        results.add(ApplicationManager.getApplication().executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(() -> {
          Runnable runnable;
          while ((runnable = tasksQueue.poll()) != null) runnable.run();
        }, ProgressWrapper.wrap(progress))));
      }
    }

    Runnable runnable;
    while ((runnable = tasksQueue.poll()) != null) runnable.run();

    for (Future<?> result : results) {
      try {
        result.get();
      }
      catch (InterruptedException ex) {
        throw new ProcessCanceledException(ex);
      }
      catch (Exception ex) {
        LOG.error(ex);
      }
    }
  }

  private void applyPushersToFile(final VirtualFile fileOrDir, @NotNull List<? extends FilePropertyPusher<?>> pushers, final Object[] moduleValues) {
    if (fileOrDir.isDirectory()) {
      fileOrDir.getChildren(); // outside read action to avoid freezes
    }

    ApplicationManager.getApplication().runReadAction(() -> {
      ProgressManager.checkCanceled();
      if (!fileOrDir.isValid()) return;
      doApplyPushersToFile(fileOrDir, pushers, moduleValues);
    });
  }
  private void doApplyPushersToFile(@NotNull VirtualFile fileOrDir, @NotNull List<? extends FilePropertyPusher<?>> pushers, Object[] moduleValues) {
    FilePropertyPusher<Object> pusher = null;
    try {
      final boolean isDir = fileOrDir.isDirectory();
      for (int i = 0; i < pushers.size(); i++) {
        //noinspection unchecked
        pusher = (FilePropertyPusher<Object>)pushers.get(i);
        if (isDir
            ? !pusher.acceptsDirectory(fileOrDir, myProject)
            : pusher.pushDirectoriesOnly() || !pusher.acceptsFile(fileOrDir, myProject)) {
          continue;
        }
        Object value = moduleValues != null ? moduleValues[i] : null;
        findAndUpdateValue(fileOrDir, pusher, value);
      }
    }
    catch (AbstractMethodError ame) { // acceptsDirectory is missed
      if (pusher != null) throw PluginException.createByClass("Failed to apply pusher " + pusher.getClass(), ame, pusher.getClass());
      throw ame;
    }
  }

  @Override
  public <T> void findAndUpdateValue(final VirtualFile fileOrDir, final FilePropertyPusher<T> pusher, final T moduleValue) {
    final T value = findPusherValuesUpwards(myProject, fileOrDir, pusher, moduleValue);
    updateValue(myProject, fileOrDir, value, pusher);
  }

  public static <T> void updateValue(final Project project, final VirtualFile fileOrDir, final T value, final FilePropertyPusher<T> pusher) {
    final T oldValue = fileOrDir.getUserData(pusher.getFileDataKey());
    if (value != oldValue) {
      fileOrDir.putUserData(pusher.getFileDataKey(), value);
      try {
        pusher.persistAttribute(project, fileOrDir, value);
      }
      catch (IOException e) {
        LOG.error(e);
      }
    }
  }

  @Override
  public void filePropertiesChanged(@NotNull final VirtualFile file) {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    FileBasedIndex.getInstance().requestReindex(file);
    for (final Project project : ProjectManager.getInstance().getOpenProjects()) {
      reloadPsi(file, project);
    }
  }

  private static void reloadPsi(final VirtualFile file, final Project project) {
    final FileManagerImpl fileManager = (FileManagerImpl)PsiManagerEx.getInstanceEx(project).getFileManager();
    if (fileManager.findCachedViewProvider(file) != null) {
      Runnable runnable = () -> WriteAction.run(() -> fileManager.forceReload(file));
      if (ApplicationManager.getApplication().isDispatchThread()) {
        runnable.run();
      } else {
        TransactionGuard.submitTransaction(project, runnable);
      }
    }
  }
}
