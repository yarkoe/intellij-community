package com.siyeh.ipp.conditional;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.util.IncorrectOperationException;
import com.siyeh.ipp.*;
import com.siyeh.ipp.base.Intention;
import com.siyeh.ipp.base.PsiElementPredicate;

public class ReplaceConditionalWithIfIntention extends Intention {

    public String getText() {
        return "Replace ?: with if-else";
    }

    public String getFamilyName() {
        return "Replace Conditional With If Else";
    }

    public PsiElementPredicate getElementPredicate() {
        return new ReplaceConditionalWithIfPredicate();
    }

    public void invoke(Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        final PsiElement element = findMatchingElement(file, editor);
        if (element instanceof PsiReturnStatement) {
            final PsiReturnStatement returnStatement = (PsiReturnStatement) element;
            final PsiConditionalExpression returnValue =
                    (PsiConditionalExpression) returnStatement.getReturnValue();
            final PsiExpression condition = returnValue.getCondition();
            final PsiExpression thenExpression = returnValue.getThenExpression();
            final PsiExpression elseExpression = returnValue.getElseExpression();
            final String ifStatementString = "if(" + condition.getText() + ')' +
                    '{' +
                    "return " + thenExpression.getText() + ';' +
                    '}' +
                    "else" +
                    '{' +
                    "return " + elseExpression.getText() + ';' +
                    '}';
            replaceStatement(project, ifStatementString, returnStatement);
        }
        else if (element instanceof PsiDeclarationStatement)
        {
            final PsiManager mgr = PsiManager.getInstance(project);
            final PsiElementFactory factory = mgr.getElementFactory();
            final PsiDeclarationStatement statement = (PsiDeclarationStatement) findMatchingElement(file, editor);
            final PsiVariable var = (PsiVariable) statement.getDeclaredElements()[0];
            final PsiConditionalExpression rhs = (PsiConditionalExpression) var.getInitializer();
            final String lhsText = var.getName();
            final String str = statement.getText();
            final int equalsIndex = str.indexOf((int) '=');
            final String declarationString = str.substring(0, equalsIndex) + ';';
            final PsiExpression condition = rhs.getCondition();
            final PsiExpression thenExpression = rhs.getThenExpression();
            final PsiExpression elseExpression = rhs.getElseExpression();
            final String ifStatementString = "if(" + condition.getText() + ')' +
                    '{' +
                    lhsText + '=' + thenExpression.getText() + ';' +
                    '}' +
                    "else" +
                    '{' +
                    lhsText + '=' + elseExpression.getText() + ';' +
                    '}';
            final PsiStatement declarationStatement = factory.createStatementFromText(declarationString, null);
            final PsiStatement ifStatement = factory.createStatementFromText(ifStatementString, null);
            PsiElement ifElement = statement.replace(ifStatement);
            final CodeStyleManager styleManager = mgr.getCodeStyleManager();
            ifElement = styleManager.reformat(ifElement);
            final PsiElement parent = ifElement.getParent();
            final PsiElement declarationElement = parent.addBefore(declarationStatement, ifElement);
            styleManager.reformat(declarationElement);
            styleManager.reformat(parent);
        }
        else
        {
            final PsiExpressionStatement statement = (PsiExpressionStatement) findMatchingElement(file, editor);
            final PsiAssignmentExpression assigmentExpression = (PsiAssignmentExpression) statement.getExpression();
            final PsiConditionalExpression rhs =
                    (PsiConditionalExpression) assigmentExpression.getRExpression();
            final PsiExpression lhs = assigmentExpression.getLExpression();
            final String lhsText = lhs.getText();
            final PsiJavaToken sign = assigmentExpression.getOperationSign();
            final String operator = sign.getText();
            final PsiExpression condition = rhs.getCondition();
            final PsiExpression thenExpression = rhs.getThenExpression();
            final PsiExpression elseExpression = rhs.getElseExpression();
            final String ifStatementString = "if(" + condition.getText() + ')' +
                    '{' +
                    lhsText + operator + thenExpression.getText() + ';' +
                    '}' +
                    "else" +
                    '{' +
                    lhsText + operator + elseExpression.getText() + ';' +
                    '}';
            replaceStatement(project, ifStatementString, statement);
        }
    }
}
