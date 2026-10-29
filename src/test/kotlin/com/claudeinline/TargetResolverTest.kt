package com.claudeinline

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TargetResolverTest : BasePlatformTestCase() {

    private fun resolve(text: String, fileName: String = "sample.py"): EditTarget {
        myFixture.configureByText(fileName, text)
        val target = TargetResolver.resolve(project, myFixture.editor, myFixture.file)
        assertEquals(
            "text must equal the document text of the range",
            myFixture.editor.document.getText(TextRange(target.startOffset, target.endOffset)),
            target.text,
        )
        return target
    }

    fun testSelectionIsExact() {
        val target = resolve("a = 1\n<selection>b = 2\nc = 3</selection>\nd = 4\n")
        assertEquals(TargetKind.SELECTION, target.kind)
        assertEquals("b = 2\nc = 3", target.text)
        assertEquals("Selection (2 lines)", target.label)
    }

    fun testSelectionInsideLineIsNotExtended() {
        val target = resolve("x = <selection>1 + 2</selection>\n")
        assertEquals(TargetKind.SELECTION, target.kind)
        assertEquals("1 + 2", target.text)
        assertEquals("Selection (1 line)", target.label)
    }

    fun testSelectionAfterIndentationExtendsToLineStart() {
        val target = resolve("def f(a, b):\n    <selection>return a + b</selection>\n")
        assertEquals(TargetKind.SELECTION, target.kind)
        assertEquals("    return a + b", target.text)
    }

    fun testSelectionAfterTabsExtendsToLineStart() {
        val target = resolve("if x:\n\t\t<selection>y = 1\n\t\tz = 2</selection>\n")
        assertEquals("\t\ty = 1\n\t\tz = 2", target.text)
    }

    fun testWholeLineSelectionEndingAtNextLineStartCountsCoveredLines() {
        val target = resolve("<selection>a = 1\nb = 2\n</selection>c = 3\n")
        assertEquals("a = 1\nb = 2\n", target.text)
        assertEquals("Selection (2 lines)", target.label)
    }

    fun testSelectionWinsOverBlankCaretLine() {
        val target = resolve("def f():\n    x = 1\n<selection>\n</selection>    y = 2\n")
        assertEquals(TargetKind.SELECTION, target.kind)
    }

    fun testBlankLineInsideFunctionIsInsert() {
        val target = resolve("def f():\n    x = 1\n<caret>\n    y = 2\n")
        assertEquals(TargetKind.INSERT, target.kind)
        assertEquals("", target.text)
        assertEquals("Insert at line 3", target.label)
        assertEquals(target.startOffset, target.endOffset)
    }

    fun testIndentedBlankLineIsInsertAndKeepsItsWhitespace() {
        val target = resolve("def f():\n    x = 1\n    <caret>\n    y = 2\n")
        assertEquals(TargetKind.INSERT, target.kind)
        assertEquals("    ", target.text)
        assertEquals("Insert at line 3", target.label)
    }

    fun testBlankLineAtEndOfFileIsInsert() {
        val target = resolve("x = 1\n<caret>")
        assertEquals(TargetKind.INSERT, target.kind)
        assertEquals("Insert at line 2", target.label)
    }

    fun testEmptyFileIsInsert() {
        val target = resolve("<caret>")
        assertEquals(TargetKind.INSERT, target.kind)
        assertEquals("Insert at line 1", target.label)
    }

    fun testCaretInMethodSelectsMethodWithIndentation() {
        val target = resolve(
            """
            class Greeter:
                def __init__(self):
                    self.n = 0

                def greet(self, name):
                    msg = "hi " + name<caret>
                    return msg

                def other(self):
                    pass
            """.trimIndent() + "\n",
        )
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("    def greet(self, name):\n        msg = \"hi \" + name\n        return msg", target.text)
        assertEquals("Function greet (lines 5-7)", target.label)
    }

    fun testCaretOnDefLine() {
        val target = resolve("class A:\n    def m<caret>eth(self):\n        pass\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("    def meth(self):\n        pass", target.text)
    }

    fun testCaretInLeadingWhitespaceOfDefLine() {
        val target = resolve("class A:\n<caret>    def meth(self):\n        pass\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("    def meth(self):\n        pass", target.text)
    }

    fun testCaretAtLineStartOfBodyLine() {
        val target = resolve("def f():\n<caret>    return 1\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("def f():\n    return 1", target.text)
    }

    fun testCaretInIndentationOfBodyLine() {
        val target = resolve("def f():\n  <caret>  return 1\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
    }

    fun testCaretAtEndOfLastLineOfFunction() {
        val target = resolve("def f():\n    return 1<caret>\n\nx = 2\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("def f():\n    return 1", target.text)
    }

    fun testCaretAfterTrailingWhitespaceOfLastLine() {
        val target = resolve("def f():\n    return 1   <caret>\n\nx = 2\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("Function f (lines 1-2)", target.label)
    }

    fun testCommentInsideFunctionIsComment() {
        val target = resolve("def f():\n    x = 1\n    # note<caret>\n    return x\n")
        assertEquals(TargetKind.COMMENT, target.kind)
        assertEquals("    # note", target.text)
        assertEquals("Comment (1 line)", target.label)
    }

    fun testCommentAloneIsComment() {
        val target = resolve("x = 1\n\n# fetch the user<caret>\n# and cache it\n\ny = 2\n")
        assertEquals(TargetKind.COMMENT, target.kind)
        assertEquals("# fetch the user\n# and cache it", target.text)
        assertEquals("Comment (2 lines)", target.label)
    }

    fun testCommentBlockOnlyCoversSameIndent() {
        val target = resolve("def f():\n    # a\n    # b<caret>\n# c\n    return 1\n")
        assertEquals("    # a\n    # b", target.text)
    }

    fun testCommentAboveDefBecomesFunction() {
        val target = resolve("x = 1\n\n# compute things<caret>\n# carefully\ndef f(a):\n    pass\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("# compute things\n# carefully\ndef f(a):\n    pass", target.text)
        assertEquals("Function f (with comment)", target.label)
    }

    fun testCommentAboveDecoratedMethodBecomesFunction() {
        val target = resolve("class A:\n    # note<caret>\n    @property\n    def v(self):\n        return 1\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("    # note\n    @property\n    def v(self):\n        return 1", target.text)
    }

    fun testCommentSeparatedFromDefByBlankLineIsComment() {
        val target = resolve("# note<caret>\n\ndef f():\n    pass\n")
        assertEquals(TargetKind.COMMENT, target.kind)
    }

    fun testTrailingCommentAfterCodeIsNotACommentLine() {
        val target = resolve("def f():\n    x = 1  # trailing<caret>\n    return x\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
        val module = resolve("y = 2  # trailing<caret>\n")
        assertEquals(TargetKind.LINE, module.kind)
    }

    fun testNestedFunctionIsInnermost() {
        val target = resolve(
            """
            def outer():
                def inner(x):
                    return x<caret> + 1
                return inner
            """.trimIndent() + "\n",
        )
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("    def inner(x):\n        return x + 1", target.text)
        assertEquals("Function inner (lines 2-3)", target.label)
    }

    fun testCaretInOuterBodyAfterNestedFunctionSelectsOuter() {
        val target = resolve(
            """
            def outer():
                def inner(x):
                    return x + 1
                return in<caret>ner
            """.trimIndent() + "\n",
        )
        assertEquals("Function outer (lines 1-4)", target.label)
        assertTrue(target.text.startsWith("def outer():"))
    }

    fun testDecoratedFunctionIncludesDecoratorsFromCaretInBody() {
        val target = resolve(
            """
            import functools

            @functools.cache
            @staticmethod
            def compute(n):
                return n<caret> * 2
            """.trimIndent() + "\n",
        )
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("@functools.cache\n@staticmethod\ndef compute(n):\n    return n * 2", target.text)
        assertEquals("Function compute (lines 3-6)", target.label)
    }

    fun testCaretOnDecoratorLineSelectsFunction() {
        val target = resolve("class A:\n    @prop<caret>erty\n    def value(self):\n        return 1\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("    @property\n    def value(self):\n        return 1", target.text)
        assertEquals("Function value (lines 2-4)", target.label)
    }

    fun testAsyncFunctionIncludesAsyncKeyword() {
        val target = resolve("async def fetch():\n    return await go()<caret>\n")
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("async def fetch():\n    return await go()", target.text)
    }

    fun testStatementAtModuleLevelIsLine() {
        val target = resolve("import os\nx = <caret>compute(1)\n\ndef f():\n    pass\n")
        assertEquals(TargetKind.LINE, target.kind)
        assertEquals("x = compute(1)", target.text)
        assertEquals("Line 2", target.label)
    }

    fun testLineAfterFunctionAtColumnZeroIsLine() {
        val target = resolve("def f():\n    return 1\n<caret>x = 2\n")
        assertEquals(TargetKind.LINE, target.kind)
        assertEquals("x = 2", target.text)
        assertEquals("Line 3", target.label)
    }

    fun testClassBodyStatementOutsideAnyMethodIsLine() {
        val target = resolve("class A:\n    limit = <caret>5\n\n    def m(self):\n        pass\n")
        assertEquals(TargetKind.LINE, target.kind)
        assertEquals("    limit = 5", target.text)
    }

    fun testNonPythonFileNeverResolvesAFunction() {
        val target = resolve("def f():\n    return<caret> 1\n", "notes.txt")
        assertEquals(TargetKind.LINE, target.kind)
        assertEquals("    return 1", target.text)
        assertEquals("Line 2", target.label)
    }

    fun testNullPsiFileFallsBackToTheDocumentsPsi() {
        myFixture.configureByText("sample.py", "def f():\n    return<caret> 1\n")
        val target = TargetResolver.resolve(project, myFixture.editor, null)
        assertEquals(TargetKind.FUNCTION, target.kind)
    }

    fun testDocumentIsCommittedBeforeResolving() {
        myFixture.configureByText("sample.py", "x = 1\n<caret>")
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength, "def g():\n    return 2\n")
        }
        myFixture.editor.caretModel.moveToOffset(document.text.indexOf("return"))
        val target = TargetResolver.resolve(project, myFixture.editor, myFixture.file)
        assertEquals(TargetKind.FUNCTION, target.kind)
        assertEquals("def g():\n    return 2", target.text)
    }
}
