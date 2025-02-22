// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.AbstractExtensionPointBean;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.util.ReflectionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ResourceBundle;

public abstract class DynamicBundle extends AbstractBundle {
  private final static Logger LOG = Logger.getInstance(DynamicBundle.class);
  private final static Method SET_PARENT = ReflectionUtil.getDeclaredMethod(ResourceBundle.class, "setParent", ResourceBundle.class);

  protected DynamicBundle(@NotNull String pathToBundle) {
    super(pathToBundle);
  }

  @Override
  protected ResourceBundle findBundle(@NotNull String pathToBundle,
                                      @NotNull ClassLoader baseLoader,
                                      @NotNull ResourceBundle.Control control) {
    ResourceBundle base = super.findBundle(pathToBundle, baseLoader, control);

    LanguageBundleEP langBundle = findLanguageBundle();
    if (langBundle == null) return base;

    ResourceBundle pluginBundle = super.findBundle(pathToBundle, langBundle.getLoaderForClass(), control);
    if (pluginBundle == null) return base;
    
    try {
      if (SET_PARENT != null) {
        SET_PARENT.invoke(pluginBundle, base);
      }
    }
    catch (Exception e) {
      LOG.warn(e);
    }
    return pluginBundle;
  }

  // todo: one language per application
  @Nullable
  private static LanguageBundleEP findLanguageBundle() {
    Application application = ApplicationManager.getApplication();
    if (application.isUnitTestMode() && !application.getExtensionArea().hasExtensionPoint(LanguageBundleEP.EP_NAME)) {
      return null;
    }
    return LanguageBundleEP.EP_NAME.findExtension(LanguageBundleEP.class);
  }

  public static final DynamicBundle INSTANCE = new DynamicBundle("") {
  };

  public static class LanguageBundleEP extends AbstractExtensionPointBean {
    public static final ExtensionPointName<LanguageBundleEP> EP_NAME = ExtensionPointName.create("com.intellij.languageBundle");
  }
}


