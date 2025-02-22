// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.ui;

import com.intellij.ide.ui.UISettings;
import com.intellij.jdkEx.JdkEx;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.MouseGestureManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.popup.util.PopupUtil;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.wm.ex.IdeFocusTraversalPolicy;
import com.intellij.openapi.wm.ex.IdeFrameEx;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.openapi.wm.impl.IdeFrameDecorator;
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl;
import com.intellij.openapi.wm.impl.IdeMenuBar;
import com.intellij.openapi.wm.impl.ProjectFrameHelper;
import com.intellij.openapi.wm.impl.customFrameDecorations.header.CustomFrameDialogContent;
import com.intellij.ui.AppUIUtil;
import com.intellij.ui.BalloonLayout;
import com.intellij.ui.ComponentUtil;
import com.intellij.ui.FrameState;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.ImageUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FrameWrapper implements Disposable, DataProvider {
  private String myDimensionKey;
  private JComponent myComponent = null;
  private JComponent myPreferredFocus = null;
  private String myTitle = "";
  private List<? extends Image> myImages = null;
  private boolean myCloseOnEsc = false;
  private BooleanGetter myOnCloseHandler;
  private Window myFrame;
  private final Map<String, Object> myDataMap = new HashMap<>();
  private Project myProject;
  private FocusWatcher myFocusWatcher;

  private boolean myDisposing;
  private boolean myDisposed;

  protected StatusBar myStatusBar;
  private final boolean myIsDialog;

  public FrameWrapper(Project project) {
    this(project, null);
  }

  public FrameWrapper(Project project, @Nullable @NonNls String dimensionServiceKey) {
    this(project, dimensionServiceKey, false);
  }

  public FrameWrapper(Project project, @Nullable @NonNls String dimensionServiceKey, boolean isDialog) {
    myDimensionKey = dimensionServiceKey;
    myIsDialog = isDialog;
    if (project != null) {
      setProject(project);
    }
  }

  public void setDimensionKey(String dimensionKey) {
    myDimensionKey = dimensionKey;
  }

  public void setData(String dataId, Object data) {
    myDataMap.put(dataId, data);
  }

  public void setProject(@NotNull Project project) {
    myProject = project;
    setData(CommonDataKeys.PROJECT.getName(), project);
    ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
      @Override
      public void projectClosing(@NotNull Project project) {
        if (project == myProject) {
          close();
        }
      }
    });
  }

  public void show() {
    show(true);
  }

  public void show(boolean restoreBounds) {
    final Window frame = getFrame();
    if (frame instanceof JFrame) {
      ((JFrame)frame).setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    }
    else {
      ((JDialog)frame).setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    }
    frame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        close();
      }
    });

    UIUtil.decorateWindowHeader(((RootPaneContainer)frame).getRootPane());

    if (frame instanceof JFrame) {
      UIUtil.setCustomTitleBar(frame, ((JFrame)frame).getRootPane(), runnable -> Disposer.register(this, () -> runnable.run()));
    }

    final WindowAdapter focusListener = new WindowAdapter() {
      @Override
      public void windowOpened(WindowEvent e) {
        IdeFocusManager fm = IdeFocusManager.getInstance(myProject);
        JComponent toFocus = getPreferredFocusedComponent();
        if (toFocus == null) {
          toFocus = fm.getFocusTargetFor(myComponent);
        }

        if (toFocus != null) {
          fm.requestFocus(toFocus, true);
        }
      }
    };
    frame.addWindowListener(focusListener);
    if (Registry.is("ide.perProjectModality")) {
      frame.setAlwaysOnTop(true);
    }
    Disposer.register(this, new Disposable() {
      @Override
      public void dispose() {
        frame.removeWindowListener(focusListener);
      }
    });
    if (myCloseOnEsc) addCloseOnEsc((RootPaneContainer)frame);

    if (IdeFrameDecorator.isCustomDecorationActive()) {
      myComponent = CustomFrameDialogContent.getCustomContentHolder(frame, myComponent);
    }

    ((RootPaneContainer)frame).getContentPane().add(myComponent, BorderLayout.CENTER);
    if (frame instanceof JFrame) {
      ((JFrame)frame).setTitle(myTitle);
    } else {
      ((JDialog)frame).setTitle(myTitle);
    }
    if (myImages != null) {
      // unwrap the image before setting as frame's icon
      frame.setIconImages(ContainerUtil.map(myImages, ImageUtil::toBufferedImage));
    }
    else {
      AppUIUtil.updateWindowIcon(myFrame);
    }

    WindowState state = myDimensionKey == null ? null : getWindowStateService(myProject).getState(myDimensionKey, frame);
    if (restoreBounds) {
      loadFrameState(state);
    }

    IdeMenuBar.bindAppMenuOfParent(frame, WindowManager.getInstance().getIdeFrame(myProject));

    myFocusWatcher = new FocusWatcher();
    myFocusWatcher.install(myComponent);
    frame.setVisible(true);
  }

  public void close() {
    if (myDisposed || (myOnCloseHandler != null && !myOnCloseHandler.get())) {
      return;
    }

    // if you remove this line problems will start happen on Mac OS X
    // 2 projects opened, call Cmd+D on the second opened project and then Esc.
    // Weird situation: 2nd IdeFrame will be active, but focus will be somewhere inside the 1st IdeFrame
    // App is unusable until Cmd+Tab, Cmd+tab
    myFrame.setVisible(false);
    Disposer.dispose(this);
  }

  @Override
  public void dispose() {
    if (isDisposed()) {
      return;
    }

    Window frame = myFrame;
    StatusBar statusBar = myStatusBar;

    myFrame = null;
    myPreferredFocus = null;
    myProject = null;
    myDataMap.clear();

    if (myComponent != null && myFocusWatcher != null) {
      myFocusWatcher.deinstall(myComponent);
    }
    myFocusWatcher = null;

    myComponent = null;
    myImages = null;
    myDisposed = true;

    if (statusBar != null) {
      Disposer.dispose(statusBar);
    }

    if (frame != null) {
      frame.setVisible(false);

      JRootPane rootPane = ((RootPaneContainer)frame).getRootPane();
      frame.removeAll();
      DialogWrapper.cleanupRootPane(rootPane);

      if (frame instanceof IdeFrame) {
        MouseGestureManager.getInstance().remove((IdeFrame)frame);
      }

      frame.dispose();

      DialogWrapper.cleanupWindowListeners(frame);
    }
  }

  public boolean isDisposed() {
    return myDisposed;
  }

  private void addCloseOnEsc(final RootPaneContainer frame) {
    JRootPane rootPane = frame.getRootPane();
    ActionListener closeAction = new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        if (!PopupUtil.handleEscKeyEvent()) {
          close();
        }
      }
    };
    rootPane.registerKeyboardAction(closeAction, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    ActionUtil.registerForEveryKeyboardShortcut(rootPane, closeAction, CommonShortcuts.getCloseActiveWindow());
  }

  public Window getFrame() {
    assert !myDisposed : "Already disposed!";

    if (myFrame == null) {
      IdeFrame parent = WindowManager.getInstance().getIdeFrame(myProject);
      myFrame = myIsDialog ? createJDialog(parent) : createJFrame(parent);
    }
    return myFrame;
  }

  protected JFrame createJFrame(IdeFrame parent) {
    return new MyJFrame(this, parent);
  }

  protected JDialog createJDialog(IdeFrame parent) {
    return new MyJDialog(this, parent);
  }

  protected IdeRootPaneNorthExtension getNorthExtension(String key) {
    return null;
  }

  @Override
  public Object getData(@NotNull @NonNls String dataId) {
    if (CommonDataKeys.PROJECT.is(dataId)) {
      return myProject;
    }
    return null;
  }

  @Nullable
  private Object getDataInner(String dataId) {
    Object data = getData(dataId);
    return data != null ? data : myDataMap.get(dataId);
  }

  public void setComponent(JComponent component) {
    myComponent = component;
  }

  public void setPreferredFocusedComponent(JComponent preferedFocus) {
    myPreferredFocus = preferedFocus;
  }

  public JComponent getPreferredFocusedComponent() {
    return myPreferredFocus;
  }

  public void closeOnEsc() {
    myCloseOnEsc = true;
  }

  public void setImage(Image image) {
    setImages(image != null ? Collections.singletonList(image) : Collections.emptyList());
  }

  public void setImages(List<? extends Image> images) {
    myImages = images;
  }

  public void setOnCloseHandler(BooleanGetter onCloseHandler) {
    myOnCloseHandler = onCloseHandler;
  }

  protected void loadFrameState(@Nullable WindowState state) {
    final Window frame = getFrame();
    if (state != null) {
      state.applyTo(frame);
    }
    else {
      final IdeFrame ideFrame = WindowManagerEx.getInstanceEx().getIdeFrame(myProject);
      if (ideFrame != null) {
        frame.setBounds(ideFrame.suggestChildFrameBounds());
      }
    }
    ((RootPaneContainer)frame).getRootPane().revalidate();
  }

  public void setTitle(String title) {
    myTitle = title;
  }

  public void addDisposable(@NotNull Disposable disposable) {
    Disposer.register(this, disposable);
  }

  protected void setStatusBar(StatusBar statusBar) {
    if (myStatusBar != null) {
      Disposer.dispose(myStatusBar);
    }
    myStatusBar = statusBar;
  }

  private static class MyJFrame extends JFrame implements DataProvider, IdeFrame.Child, IdeFrameEx {
    private static final boolean USE_SINGLE_SYSTEM_MENUBAR = SystemInfo.isMacSystemMenu && "true".equalsIgnoreCase(System.getProperty("mac.system.menu.singleton"));
    private FrameWrapper myOwner;
    private final IdeFrame myParent;

    private String myFrameTitle;
    private String myFileTitle;
    private File myFile;

    private MyJFrame(FrameWrapper owner, IdeFrame parent) throws HeadlessException {
      myOwner = owner;
      myParent = parent;
      FrameState.setFrameStateListener(this);
      setGlassPane(new IdeGlassPaneImpl(getRootPane(), true));

      final boolean setMenuOnFrame = SystemInfo.isMac && !USE_SINGLE_SYSTEM_MENUBAR;

      if (setMenuOnFrame) {
        setJMenuBar(IdeMenuBar.createMenuBar());
      }

      MouseGestureManager.getInstance().add(this);
      setFocusTraversalPolicy(new IdeFocusTraversalPolicy());
    }

    @Override
    public boolean isInFullScreen() {
      return false;
    }

    @NotNull
    @Override
    public Promise<?> toggleFullScreen(boolean state) {
      return Promises.resolvedPromise();
    }

    @Override
    public void addNotify() {
      if (IdeFrameDecorator.isCustomDecorationActive()) {
        JdkEx.setHasCustomDecoration(this);
      }
      super.addNotify();
    }

    @Override
    public JComponent getComponent() {
      return getRootPane();
    }

    @Nullable
    @Override
    public StatusBar getStatusBar() {
      StatusBar ownerBar = myOwner != null ? myOwner.myStatusBar : null;
      return ownerBar != null ? ownerBar : myParent != null ? myParent.getStatusBar() : null;
    }

    @NotNull
    @Override
    public Rectangle suggestChildFrameBounds() {
      return myParent != null ? myParent.suggestChildFrameBounds() : getOnScreenBounds();
    }

    private static Rectangle getOnScreenBounds() {
      Rectangle r = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .getDefaultScreenDevice()
        .getDefaultConfiguration()
        .getBounds();
      int margin = r.width / 20; // 1/20th or 5% from each side
      return new Rectangle(r.x + margin, r.y + margin, r.width - margin * 2, r.height - margin * 2);
    }

    @Override
    public Project getProject() {
      return myParent != null ? myParent.getProject() : ProjectManager.getInstance().getDefaultProject();
    }

    @Override
    public void setFrameTitle(String title) {
      myFrameTitle = title;
      updateTitle();
    }

    @Override
    public void setFileTitle(String fileTitle, File ioFile) {
      myFileTitle = fileTitle;
      myFile = ioFile;
      updateTitle();
    }

    @Nullable
    @Override
    public IdeRootPaneNorthExtension getNorthExtension(String key) {
      return myOwner.getNorthExtension(key);
    }

    @Nullable
    @Override
    public BalloonLayout getBalloonLayout() {
      return null;
    }

    private void updateTitle() {
      ProjectFrameHelper.updateTitle(this, myFrameTitle, myFileTitle, myFile);
    }

    @Override
    public void dispose() {
      FrameWrapper owner = myOwner;
      myOwner = null;
      if (owner == null || owner.myDisposing) return;
      owner.myDisposing = true;
      Disposer.dispose(owner);
      super.dispose();
      rootPane = null;
      setMenuBar(null);
    }

    @Override
    public Object getData(@NotNull String dataId) {
      if (IdeFrame.KEY.getName().equals(dataId)) {
        return this;
      }
      return myOwner == null ? null : myOwner.getDataInner(dataId);
    }

    @Override
    public void paint(Graphics g) {
      UISettings.setupAntialiasing(g);
      super.paint(g);
    }
  }

  private static class MyJDialog extends JDialog implements DataProvider, IdeFrame.Child {
    private FrameWrapper myOwner;
    private final IdeFrame myParent;

    private MyJDialog(FrameWrapper owner, IdeFrame parent) throws HeadlessException {
      super(ComponentUtil.getWindow(parent.getComponent()));
      myOwner = owner;
      myParent = parent;
      setGlassPane(new IdeGlassPaneImpl(getRootPane()));
      getRootPane().putClientProperty("Window.style", "small");
      setBackground(UIUtil.getPanelBackground());
      MouseGestureManager.getInstance().add(this);
      setFocusTraversalPolicy(new IdeFocusTraversalPolicy());
    }

    @Override
    public JComponent getComponent() {
      return getRootPane();
    }

    @Nullable
    @Override
    public StatusBar getStatusBar() {
      return null;
    }

    @Nullable
    @Override
    public BalloonLayout getBalloonLayout() {
      return null;
    }

    @NotNull
    @Override
    public Rectangle suggestChildFrameBounds() {
      return myParent.suggestChildFrameBounds();
    }

    @Override
    public Project getProject() {
      return myParent.getProject();
    }

    @Override
    public void setFrameTitle(String title) {
      setTitle(title);
    }

    @Override
    public void dispose() {
      FrameWrapper owner = myOwner;
      myOwner = null;
      if (owner == null || owner.myDisposing) return;
      owner.myDisposing = true;
      Disposer.dispose(owner);
      super.dispose();
      rootPane = null;
    }

    @Override
    public Object getData(@NotNull String dataId) {
      if (IdeFrame.KEY.getName().equals(dataId)) {
        return this;
      }
      return myOwner == null ? null : myOwner.getDataInner(dataId);
    }

    @Override
    public void paint(Graphics g) {
      UISettings.setupAntialiasing(g);
      super.paint(g);
    }
  }


  public void setLocation(Point location) {
    getFrame().setLocation(location);
  }

  public void setSize(Dimension size) {
    getFrame().setSize(size);
  }

  @NotNull
  private static WindowStateService getWindowStateService(@Nullable Project project) {
    return project == null ? WindowStateService.getInstance() : WindowStateService.getInstance(project);
  }
}
