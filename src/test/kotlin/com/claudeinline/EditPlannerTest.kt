package com.claudeinline

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditPlannerTest : BasePlatformTestCase() {

    private val document get() = myFixture.editor.document

    /** Region = the `<selection>` markers, else an empty region at `<caret>`, else the whole text. */
    private fun plan(
        text: String,
        replacement: String,
        imports: List<String>,
        fileName: String = "sample.py",
    ): EditPlan {
        myFixture.configureByText(fileName, text)
        val selection = myFixture.editor.selectionModel
        val caret = myFixture.editor.caretModel.offset
        val region = when {
            selection.hasSelection() -> TextRange(selection.selectionStart, selection.selectionEnd)
            text.contains("<caret>") -> TextRange(caret, caret)
            else -> TextRange(0, document.textLength)
        }
        return EditPlanner.plan(document, myFixture.file, region, EditResponse(replacement, imports))
    }

    private fun assertAppliesTo(plan: EditPlan, expected: String) {
        assertEquals(expected, plan.resultText)
        WriteCommandAction.runWriteCommandAction(project) { plan.apply(document) }
        assertEquals("apply() must produce resultText", plan.resultText, document.text)
    }

    fun testImportsGoAfterLastImport() {
        val plan = plan(
            "import os\nimport sys\n\n\n<selection>def f():\n    pass</selection>\n",
            "def f():\n    return json.dumps({})",
            listOf("import json"),
        )
        assertAppliesTo(plan, "import os\nimport sys\nimport json\n\n\ndef f():\n    return json.dumps({})\n")
    }

    fun testSeveralImportsKeepTheirOrder() {
        val plan = plan(
            "from a import b\n\n<selection>x = 1</selection>\n",
            "x = c.d() + e",
            listOf("import c", "from f import e"),
        )
        assertAppliesTo(plan, "from a import b\nimport c\nfrom f import e\n\nx = c.d() + e\n")
    }

    fun testImportsGoAfterDocstringWhenThereAreNoImports() {
        val plan = plan(
            "\"\"\"Module doc.\n\nMore.\n\"\"\"\n\n<selection>def f():\n    pass</selection>\n",
            "def f():\n    return os.getcwd()",
            listOf("import os"),
        )
        assertAppliesTo(
            plan,
            "\"\"\"Module doc.\n\nMore.\n\"\"\"\n\nimport os\n\ndef f():\n    return os.getcwd()\n",
        )
    }

    fun testImportsGoToTheTopWhenNothingPrecedes() {
        val plan = plan(
            "x = 1\n<selection>def f():\n    pass</selection>\n",
            "def f():\n    return os.sep",
            listOf("import os"),
        )
        assertAppliesTo(plan, "import os\nx = 1\ndef f():\n    return os.sep\n")
    }

    fun testImportsOnAnEmptyDocument() {
        val plan = plan("", "print(os.sep)", listOf("import os"))
        assertAppliesTo(plan, "import os\nprint(os.sep)")
    }

    fun testImportAtOffsetZeroComesBeforeARegionStartingThere() {
        val plan = plan(
            "<selection>def f():\n    pass</selection>\n",
            "def f():\n    return os.sep",
            listOf("import os"),
        )
        assertAppliesTo(plan, "import os\ndef f():\n    return os.sep\n")
    }

    fun testLastImportWithoutTrailingNewline() {
        val plan = plan("<selection>import os</selection>", "import os\nprint(1)", listOf("import sys"))
        assertAppliesTo(plan, "import os\nprint(1)\nimport sys")
    }

    fun testImportsAlreadyPresentAreNotAdded() {
        val plan = plan(
            "import os\nimport sys\n\n<selection>x = 1</selection>\n",
            "x = os.sep",
            listOf("import os", "import sys"),
        )
        assertAppliesTo(plan, "import os\nimport sys\n\nx = os.sep\n")
    }

    fun testOnlyMissingImportsAreAdded() {
        val plan = plan(
            "import os\n\n<selection>x = 1</selection>\n",
            "x = os.sep + sys.platform",
            listOf("import os", "import sys"),
        )
        assertAppliesTo(plan, "import os\nimport sys\n\nx = os.sep + sys.platform\n")
    }

    fun testNoImportsRequested() {
        val plan = plan("import os\n\n<selection>x = 1</selection>\n", "x = 2", emptyList())
        assertAppliesTo(plan, "import os\n\nx = 2\n")
    }

    fun testImportsAreSkippedWhenTheRegionContainsTheInsertionPoint() {
        val plan = plan(
            "<selection>import os\n\ndef f():\n    pass</selection>\n",
            "import os\n\ndef f():\n    return sys.platform",
            listOf("import sys"),
        )
        assertAppliesTo(plan, "import os\n\ndef f():\n    return sys.platform\n")
    }

    fun testImportsAreSkippedWhenTheRegionContainsTheDocstringInsertionPoint() {
        val plan = plan(
            "<selection>\"\"\"Doc.\"\"\"\n\nx = 1</selection>\n",
            "\"\"\"Doc.\"\"\"\n\nx = sys.platform",
            listOf("import sys"),
        )
        assertAppliesTo(plan, "\"\"\"Doc.\"\"\"\n\nx = sys.platform\n")
    }

    fun testImportsStillAddedWhenTheRegionIsTheLastImportLine() {
        val plan = plan(
            "import os\n<selection>import sys</selection>\nx = 1\n",
            "import sys as system",
            listOf("import json"),
        )
        assertAppliesTo(plan, "import os\nimport sys as system\nimport json\nx = 1\n")
    }

    fun testImportsStillAddedWhenTheRegionStartsRightAfterTheInsertionLine() {
        val plan = plan(
            "import os\n<caret>\nx = 1\n",
            "y = json.loads('1')",
            listOf("import json"),
        )
        assertAppliesTo(plan, "import os\nimport json\ny = json.loads('1')\nx = 1\n")
    }

    fun testImportsAreIgnoredOutsidePythonFiles() {
        val plan = plan("import os\n<selection>hello</selection>\n", "world", listOf("import sys"), "notes.txt")
        assertAppliesTo(plan, "import os\nworld\n")
    }

    fun testLateImportsAfterCodeDoNotMoveTheInsertionPoint() {
        val plan = plan(
            "import os\n\nx = 1\n\nimport sys\n\n<selection>y = 2</selection>\n",
            "y = json.loads('2')",
            listOf("import json"),
        )
        assertAppliesTo(plan, "import os\nimport json\n\nx = 1\n\nimport sys\n\ny = json.loads('2')\n")
    }

    fun testImportAfterFutureImportAndConditionalImportsStaysAtTheTopBlock() {
        val plan = plan(
            "from __future__ import annotations\nimport os\n\ntry:\n    import ujson\nexcept ImportError:\n    ujson = None\n\n<selection>y = 2</selection>\n",
            "y = sys.maxsize",
            listOf("import sys"),
        )
        assertAppliesTo(
            plan,
            "from __future__ import annotations\nimport os\nimport sys\n\ntry:\n    import ujson\nexcept ImportError:\n    ujson = None\n\ny = sys.maxsize\n",
        )
    }

    fun testReplacementIsLfNormalized() {
        val plan = plan("<selection>x = 1</selection>\n", "if x:\r\n    y = 2\rz = 3", emptyList())
        assertAppliesTo(plan, "if x:\n    y = 2\nz = 3\n")
    }

    fun testOriginalTextIsTheWholeDocumentBeforeTheEdit() {
        val plan = plan("a = 1\n<selection>b = 2</selection>\nc = 3\n", "b = 20", emptyList())
        assertEquals("a = 1\nb = 2\nc = 3\n", plan.originalText)
        assertEquals("a = 1\nb = 20\nc = 3\n", plan.resultText)
    }

    fun testApplyIsOneUndoableCommand() {
        val plan = plan("import os\n\n<selection>x = 1</selection>\n", "x = sys.platform", listOf("import sys"))
        val original = plan.originalText
        WriteCommandAction.runWriteCommandAction(project, "Inline AI Edit", null, { plan.apply(document) })
        assertEquals(plan.resultText, document.text)
        val undoManager = UndoManager.getInstance(project)
        val fileEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        assertTrue(undoManager.isUndoAvailable(fileEditor))
        undoManager.undo(fileEditor)
        assertEquals(original, document.text)
    }

    fun testApplyRejectsAChangedDocument() {
        val plan = plan("a = 1\n<selection>b = 2</selection>\n", "b = 3", emptyList())
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "# new\n") }
        try {
            WriteCommandAction.runWriteCommandAction(project) { plan.apply(document) }
            fail("apply must refuse a document that changed after planning")
        } catch (expected: IllegalStateException) {
            assertEquals("# new\na = 1\nb = 2\n", document.text)
        }
    }

    fun testApplyReportsTheNewRegionAndRevertRestoresTheOriginal() {
        for (imports in listOf(emptyList(), listOf("import json"))) {
            val plan = plan("import os\n\n<selection>def f():\n    pass</selection>\n", "def f():\n    return json.dumps(1)", imports)
            val original = plan.originalText
            val applied = WriteCommandAction.runWriteCommandAction<AppliedEdit>(project) { plan.apply(document) }
            assertEquals("def f():\n    return json.dumps(1)", document.getText(TextRange(applied.regionStart, applied.regionEnd)))
            assertEquals(imports.size, applied.importMarkers.size)
            WriteCommandAction.runWriteCommandAction(project) {
                EditReverter.revert(document, TextRange(applied.regionStart, applied.regionEnd), "def f():\n    pass", applied.importMarkers)
            }
            assertEquals(original, document.text)
        }
    }

    fun testRevertWithImportAtOffsetZero() {
        val plan = plan("<selection>def f():\n    pass</selection>\n", "def f():\n    return os.sep", listOf("import os"))
        val original = plan.originalText
        val applied = WriteCommandAction.runWriteCommandAction<AppliedEdit>(project) { plan.apply(document) }
        assertEquals("import os\ndef f():\n    return os.sep\n", document.text)
        assertEquals("def f():\n    return os.sep", document.getText(TextRange(applied.regionStart, applied.regionEnd)))
        WriteCommandAction.runWriteCommandAction(project) {
            EditReverter.revert(document, TextRange(applied.regionStart, applied.regionEnd), "def f():\n    pass", applied.importMarkers)
        }
        assertEquals(original, document.text)
    }

    fun testRevertWithImportAfterTheRegion() {
        val plan = plan("<selection>import os</selection>", "import os\nprint(1)", listOf("import sys"))
        val original = plan.originalText
        val applied = WriteCommandAction.runWriteCommandAction<AppliedEdit>(project) { plan.apply(document) }
        assertEquals("import os\nprint(1)\nimport sys", document.text)
        WriteCommandAction.runWriteCommandAction(project) {
            EditReverter.revert(document, TextRange(applied.regionStart, applied.regionEnd), "import os", applied.importMarkers)
        }
        assertEquals(original, document.text)
    }
}
