// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.util;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility methods to support Java records
 */
public class JavaPsiRecordUtil {
  /**
   * @param accessor accessor method for record component 
   * @return a corresponding record component, or null if the supplied method is not an accessor for the record component.
   * Note that if accessor is not well-formed (e.g. has wrong return type), the corresponding record component will still be returned.
   */
  @Nullable
  public static PsiRecordComponent getRecordComponentForAccessor(@NotNull PsiMethod accessor) {
    PsiClass aClass = accessor.getContainingClass();
    if (aClass == null || !aClass.isRecord()) return null;
    if (!accessor.getParameterList().isEmpty()) return null;
    String name = accessor.getName();
    for (PsiRecordComponent c : aClass.getRecordComponents()) {
      if (name.equals(c.getName())) {
        return c;
      }
    }
    return null;
  }

  /**
   * @param component record component
   * @return synthetic field that corresponds to given component, or null if not found (e.g. if this component doesn't belong to a class) 
   */
  @Nullable
  public static PsiField getFieldForComponent(@NotNull PsiRecordComponent component) {
    PsiClass aClass = component.getContainingClass();
    if (aClass == null) return null;
    String name = component.getName();
    for (PsiField field : aClass.getFields()) {
      if (field.getName().equals(name) && !field.hasModifierProperty(PsiModifier.STATIC)) {
        return field;
      }
    }
    return null;
  }

  /**
   * @param method method to check
   * @return true if given method is a compact constructor (has no parameter list),
   * regardless whether it's declared in the record or not
   */
  public static boolean isCompactConstructor(@NotNull PsiMethod method) {
    return method.isConstructor() && method.getParameterList().textMatches("");
  }

  /**
   * @param method method to check
   * @return true if given method is a canonical (non-compact) constructor for a record class
   */
  public static boolean isCanonicalConstructor(@NotNull PsiMethod method) {
    if (!method.isConstructor()) return false;
    PsiClass aClass = method.getContainingClass();
    if (aClass == null || !aClass.isRecord()) return false;
    return hasCanonicalSignature(method, aClass.getRecordComponents());
  }

  private static boolean hasCanonicalSignature(@NotNull PsiMethod method, PsiRecordComponent[] components) {
    PsiParameter[] parameters = method.getParameterList().getParameters();
    if (components.length != parameters.length) return false;
    for (int i = 0; i < parameters.length; i++) {
      PsiType componentType = components[i].getType();
      PsiType parameterType = parameters[i].getType();
      if (!TypeConversionUtil.erasure(componentType).equals(TypeConversionUtil.erasure(parameterType))) return false;
    }
    return true;
  }

  /**
   * @param recordClass record class
   * @return first explicitly declared canonical or compact constructor; 
   * null if no canonical and compact constructor declared or the supplied class is not a record
   */
  @Nullable
  public static PsiMethod findCanonicalConstructor(@NotNull PsiClass recordClass) {
    if (!recordClass.isRecord()) return null;
    PsiMethod[] constructors = recordClass.getConstructors();
    if (constructors.length == 0) return null;
    PsiRecordComponent[] components = recordClass.getRecordComponents();
    for (PsiMethod constructor : constructors) {
      if (isCompactConstructor(constructor) || hasCanonicalSignature(constructor, components)) {
        return constructor;
      }
    }
    return null;
  }
}
