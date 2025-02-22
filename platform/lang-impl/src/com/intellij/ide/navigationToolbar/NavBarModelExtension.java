// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.navigationToolbar;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Collection;

/**
 * The interface has a default implementation ({@link DefaultNavBarExtension}) which is normally registered as last.
 * That means that custom implementations are called before the default one - with the exception of {@link #adjustElement(PsiElement)}
 * method, for which the order is reverse.
 *
 * @author anna
 */
public interface NavBarModelExtension {
  ExtensionPointName<NavBarModelExtension> EP_NAME = ExtensionPointName.create("com.intellij.navbar");

  @Nullable
  default Icon getIcon(Object object) { return null; }

  @Nullable
  String getPresentableText(Object object);

  @Nullable
  PsiElement getParent(PsiElement psiElement);

  @Nullable
  PsiElement adjustElement(PsiElement psiElement);

  @NotNull
  Collection<VirtualFile> additionalRoots(Project project);

  @Nullable
  default Object getData(@NotNull String dataId, @NotNull DataProvider provider) { return null; }

  @Nullable
  default String getPopupMenuGroup(@NotNull DataProvider provider) { return null; }

  default PsiElement getLeafElement(@NotNull DataContext dataContext) {
    return null;
  }
}
