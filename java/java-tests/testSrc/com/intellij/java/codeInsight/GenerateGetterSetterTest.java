// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInsight;

import com.intellij.codeInsight.generation.*;
import com.intellij.ide.util.MemberChooser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.codeStyle.JavaCodeStyleSettings;
import com.intellij.psi.impl.light.LightFieldBuilder;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.NotNullFunction;
import com.intellij.util.VisibilityUtil;
import com.intellij.util.ui.UIUtil;
import com.siyeh.ig.style.UnqualifiedFieldAccessInspection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public class GenerateGetterSetterTest extends LightJavaCodeInsightFixtureTestCase {
  public void testDoNotStripIsOfNonBooleanFields() {
    myFixture.addClass("class YesNoRAMField {}");
    myFixture.configureByText("a.java", """
      class Foo {
          YesNoRAMField isStateForceMailField;

          <caret>
      }
      """);
    generateGetter();
    myFixture.checkResult("""
                            class Foo {
                                YesNoRAMField isStateForceMailField;

                                public YesNoRAMField getIsStateForceMailField() {
                                    return isStateForceMailField;
                                }
                            }
                            """);
  }

  public void testStripIsOfBooleanFields() {
    myFixture.configureByText("a.java", """
      class Foo {
          static final String CONST = "const";
          boolean isStateForceMailField;
          boolean isic;

          <caret>
      }
      """);
    generateGetter();
    myFixture.checkResult("""
                            class Foo {
                                static final String CONST = "const";
                                boolean isStateForceMailField;
                                boolean isic;

                                public boolean isStateForceMailField() {
                                    return isStateForceMailField;
                                }

                                public boolean isIsic() {
                                    return isic;
                                }
                            }
                            """);
  }

  public void testStripIsOfBooleanFieldsSetter() {
    myFixture.configureByText("a.java", """
      class Foo {
          boolean isStateForceMailField;

          <caret>
      }
      """);
    generateSetter();
    myFixture.checkResult("""
                            class Foo {
                                boolean isStateForceMailField;

                                public void setStateForceMailField(boolean stateForceMailField) {
                                    isStateForceMailField = stateForceMailField;
                                }
                            }
                            """);
  }

  public void testBuilderSetterTemplate() {
    myFixture.configureByText("a.java", """
      class X<T extends String> {
         T field;
        \s
         <caret>
      }
      """);
    try {
      SetterTemplatesManager.getInstance().getState().defaultTemplateName = "Builder";
      generateSetter();
      myFixture.checkResult("""
                              class X<T extends String> {
                                 T field;

                                  public X<T> setField(T field) {
                                      this.field = field;
                                      return this;
                                  }
                              }
                              """);
    }
    finally {
      SetterTemplatesManager.getInstance().getState().defaultTemplateName = null;
    }
  }

  public void testStripFieldPrefix() {
    JavaCodeStyleSettings settings = JavaCodeStyleSettings.getInstance(getProject());
    settings.FIELD_NAME_PREFIX = "my";
    myFixture.configureByText("a.java", """
       class Foo {
           String myName;

           <caret>
       }
      \s""");
    generateGetter();
    myFixture.checkResult("""
                             class Foo {
                                 String myName;

                                 public String getName() {
                                     return myName;
                                 }
                             }
                            \s""");
  }

  public void testQualifiedThis() {
    myFixture.enableInspections(UnqualifiedFieldAccessInspection.class);
    myFixture.configureByText("a.java", """
      class Foo {
          boolean isStateForceMailField;

          <caret>
      }
      """);
    generateGetter();
    myFixture.checkResult("""
                            class Foo {
                                boolean isStateForceMailField;

                                public boolean isStateForceMailField() {
                                    return this.isStateForceMailField;
                                }
                            }
                            """);
  }

  public void testNullableStuff() {
    myFixture.addClass("package org.jetbrains.annotations;\n" + "public @interface NotNull {}");
    myFixture.configureByText("a.java", """
      class Foo {
          @org.jetbrains.annotations.NotNull
          private String myName;

          <caret>
      }
      """);
    generateGetter();
    generateSetter();
    myFixture.checkResult("""
                            import org.jetbrains.annotations.NotNull;

                            class Foo {
                                @org.jetbrains.annotations.NotNull
                                private String myName;

                                public void setMyName(@NotNull String myName) {
                                    this.myName = myName;
                                }

                                @NotNull
                                public String getMyName() {
                                    return myName;
                                }
                            }
                            """);
  }

  public void testLombokGeneratedFieldsWithoutContainingFile() {
    ServiceContainerUtil.registerExtension(ApplicationManager.getApplication(), GenerateAccessorProviderRegistrar.EP_NAME,
                                           new NotNullFunction<PsiClass, Collection<EncapsulatableClassMember>>() {
                                             @NotNull
                                             @Override
                                             public Collection<EncapsulatableClassMember> fun(PsiClass dom) {
                                               LightFieldBuilder builder =
                                                 new LightFieldBuilder(PsiManager.getInstance(getProject()), "lombokGenerated",
                                                                       PsiTypes.intType());
                                               builder.setContainingClass(dom);
                                               return List.of(new PsiFieldMember(builder));
                                             }
                                           }, getTestRootDisposable());
    myFixture.configureByText("a.java", """
      class A {  \s
          private String myName;

          <caret>
      }
      """);
    new GenerateGetterHandler() {
      @Override
      protected ClassMember[] chooseMembers(ClassMember[] members,
                                            boolean allowEmptySelection,
                                            boolean copyJavadocCheckbox,
                                            Project project,
                                            @Nullable Editor editor) {
        final MemberChooser<ClassMember> chooser = super.createMembersChooser(members, allowEmptySelection, copyJavadocCheckbox, project);
        Disposer.register(getTestRootDisposable(), () -> chooser.close(DialogWrapper.OK_EXIT_CODE));
        return members;
      }
    }.invoke(getProject(), myFixture.getEditor(), myFixture.getFile());
    UIUtil.dispatchAllInvocationEvents();
    myFixture.checkResult("""
                            class A {  \s
                                private String myName;

                                public String getMyName() {
                                    return myName;
                                }

                                public int getLombokGenerated() {
                                    return lombokGenerated;
                                }
                            }
                            """);
  }

  private void generateGetter() {
    new GenerateGetterHandler() {
      @Override
      protected ClassMember[] chooseMembers(ClassMember[] members,
                                            boolean allowEmptySelection,
                                            boolean copyJavadocCheckbox,
                                            Project project,
                                            @Nullable Editor editor) {
        return members;
      }
    }.invoke(getProject(), myFixture.getEditor(), myFixture.getFile());
    UIUtil.dispatchAllInvocationEvents();
  }

  public void testStaticOrThisSetterWithSameNameParameter() {
    myFixture.enableInspections(UnqualifiedFieldAccessInspection.class);
    myFixture.configureByText("a.java", """
      class Foo {
          static int p;
          int f;

          <caret>
      }
      """);
    generateSetter();
    myFixture.checkResult("""
                            class Foo {
                                static int p;
                                int f;

                                public static void setP(int p) {
                                    Foo.p = p;
                                }

                                public void setF(int f) {
                                    this.f = f;
                                }
                            }
                            """);
  }

  public void testInvokeBetweenCommentAndMethod() {
    myFixture.enableInspections(UnqualifiedFieldAccessInspection.class);
    myFixture.configureByText("a.java", """
      class Foo {
        int a;
        //comment
       <caret> void foo() {}
      }""");
    generateGetter();
    myFixture.checkResult("""
                            class Foo {
                              int a;

                                public int getA() {
                                    return this.a;
                                }

                                //comment
                              void foo() {}
                            }""");
  }

  public void testRecordAccessor() {
    doRecordAccessorTest(VisibilityUtil.ESCALATE_VISIBILITY);
    doRecordAccessorTest(PsiModifier.PRIVATE);
  }

  private void doRecordAccessorTest(String visibility) {
    JavaCodeStyleSettings.getInstance(getProject()).VISIBILITY = visibility;
    myFixture.configureByText("a.java", """
      record Point(int x, int y) {
        <caret>
      }
      """);
    generateGetter();
    myFixture.checkResult("""
                            record Point(int x, int y) {
                                @Override
                                public int x() {
                                    return x;
                                }

                                @Override
                                public int y() {
                                    return y;
                                }
                            }
                            """);
  }

  private void generateSetter() {
    new GenerateSetterHandler() {
      @Override
      protected ClassMember[] chooseMembers(ClassMember[] members,
                                            boolean allowEmptySelection,
                                            boolean copyJavadocCheckbox,
                                            Project project,
                                            @Nullable Editor editor) {
        return members;
      }
    }.invoke(getProject(), myFixture.getEditor(), myFixture.getFile());
    UIUtil.dispatchAllInvocationEvents();
  }
}
