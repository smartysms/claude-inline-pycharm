package com.claudeinline

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportInsertionTest {
    @Test
    fun dropsStatementsAlreadyPresent() {
        val doc = "import os\nfrom typing import List\n\ndef f():\n    pass\n"
        val result = ImportInsertion.missingImports(listOf("import os", "import sys", "from typing import List"), doc)
        assertEquals(listOf("import sys"), result)
    }

    @Test
    fun keepsAllWhenNonePresent() {
        val result = ImportInsertion.missingImports(listOf("import json", "from a import b"), "x = 1\n")
        assertEquals(listOf("import json", "from a import b"), result)
    }

    @Test
    fun returnsEmptyWhenAllPresent() {
        val doc = "import os\nimport sys\n"
        assertEquals(emptyList<String>(), ImportInsertion.missingImports(listOf("import sys", "import os"), doc))
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertEquals(emptyList<String>(), ImportInsertion.missingImports(emptyList(), "import os\n"))
    }

    @Test
    fun deduplicatesKeepingFirstOccurrence() {
        val result = ImportInsertion.missingImports(listOf("import a", "import b", "import a", "import b"), "")
        assertEquals(listOf("import a", "import b"), result)
    }

    @Test
    fun keepsRequestedOrder() {
        val result = ImportInsertion.missingImports(listOf("import z", "import a", "import m"), "import a\n")
        assertEquals(listOf("import z", "import m"), result)
    }

    @Test
    fun comparesTrimmedLines() {
        val doc = "  import os  \r\nfrom x import y\t\n"
        val result = ImportInsertion.missingImports(listOf("  import os", "from x import y  ", "import re"), doc)
        assertEquals(listOf("import re"), result)
    }

    @Test
    fun dropsBlankEntries() {
        assertEquals(listOf("import a"), ImportInsertion.missingImports(listOf("", "  ", "import a"), ""))
    }

    @Test
    fun partialLineMatchDoesNotCount() {
        val doc = "import os, sys\n# import re\n"
        val result = ImportInsertion.missingImports(listOf("import os", "import re"), doc)
        assertEquals(listOf("import os", "import re"), result)
    }

    @Test
    fun importInsideFunctionBodyCountsAsPresent() {
        val doc = "def f():\n    import json\n"
        assertEquals(emptyList<String>(), ImportInsertion.missingImports(listOf("import json"), doc))
    }
}
