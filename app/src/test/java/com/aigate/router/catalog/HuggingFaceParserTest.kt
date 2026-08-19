package com.aigate.router.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт разбора ответов huggingface.co/api. Фикстуры повторяют реальную
 * выдачу: в дереве репозитория рядом с весами лежат README, конфиги и шарды.
 */
class HuggingFaceParserTest {

    @Test
    fun `поиск отдаёт идентификатор, счётчики и теги`() {
        val search = """
            [
              {"id":"Qwen/Qwen3-4B-GGUF","modelId":"Qwen/Qwen3-4B-GGUF",
               "downloads":184213,"likes":512,
               "tags":["gguf","text-generation","qwen3"],
               "pipeline_tag":"text-generation"},
              {"id":"bartowski/gemma-3-1b-it-GGUF",
               "downloads":9214,"likes":37,
               "tags":["gguf"],
               "pipeline_tag":"text-generation"}
            ]
        """.trimIndent()

        val repos = HuggingFaceParser.parseSearch(search)

        assertEquals(2, repos.size)
        val first = repos[0]
        assertEquals("Qwen/Qwen3-4B-GGUF", first.repoId)
        assertEquals(184_213L, first.downloads)
        assertEquals(512L, first.likes)
        assertEquals(listOf("gguf", "text-generation", "qwen3"), first.tags)
        assertEquals("text-generation", first.pipelineTag)
        assertEquals("bartowski/gemma-3-1b-it-GGUF", repos[1].repoId)
    }

    @Test
    fun `отсутствующие поля поиска не теряют запись`() {
        val search = """[{"modelId":"unsloth/Qwen3-8B-GGUF"}]"""

        val repo = HuggingFaceParser.parseSearch(search).single()

        // id может отсутствовать — тогда идентификатор берётся из modelId.
        assertEquals("unsloth/Qwen3-8B-GGUF", repo.repoId)
        assertEquals(0L, repo.downloads)
        assertEquals(0L, repo.likes)
        assertTrue(repo.tags.isEmpty())
        assertNull(repo.pipelineTag)
    }

    @Test
    fun `запись без идентификатора пропускается`() {
        val search = """[{"downloads":100,"likes":1},{"id":"user/model-GGUF"}]"""

        assertEquals(listOf("user/model-GGUF"), HuggingFaceParser.parseSearch(search).map { it.repoId })
    }

    @Test
    fun `битый ответ поиска даёт пустой список`() {
        assertTrue(HuggingFaceParser.parseSearch("не json").isEmpty())
        assertTrue(HuggingFaceParser.parseSearch("""{"error":"Repository not found"}""").isEmpty())
        assertTrue(HuggingFaceParser.parseSearch("").isEmpty())
    }

    private val tree = """
        [
          {"type":"file","path":".gitattributes","size":1519},
          {"type":"file","path":"README.md","size":2841},
          {"type":"file","path":"config.json","size":1042},
          {"type":"file","path":"preview.png","size":184320},
          {"type":"directory","path":"quantized"},
          {"type":"file","path":"Qwen3-4B-Q4_K_M.gguf","size":135,
           "lfs":{"oid":"9f2c1d4e5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f",
                  "size":2497281440,"pointerSize":135}},
          {"type":"file","path":"Qwen3-4B-Q8_0.gguf","size":4692373504},
          {"type":"file","path":"gemma-3-1b-it-int4.litertlm","size":135,
           "lfs":{"oid":"aa11bb22cc33dd44ee55ff6677889900aabbccddeeff00112233445566778899",
                  "size":1048576000,"pointerSize":135}},
          {"type":"file","path":"Qwen3-30B-A3B-Q4_K_M-00001-of-00002.gguf","size":135,
           "lfs":{"oid":"1111","size":16000000000,"pointerSize":135}},
          {"type":"file","path":"Qwen3-30B-A3B-Q4_K_M-00002-of-00002.gguf","size":135,
           "lfs":{"oid":"2222","size":15300000000,"pointerSize":135}}
        ]
    """.trimIndent()

    @Test
    fun `в дереве остаются только файлы моделей`() {
        val files = HuggingFaceParser.parseTree(tree).map { it.path }

        assertEquals(
            listOf("Qwen3-4B-Q4_K_M.gguf", "Qwen3-4B-Q8_0.gguf", "gemma-3-1b-it-int4.litertlm"),
            files
        )
    }

    @Test
    fun `многочастные шарды не попадают в каталог`() {
        // Части по 15 ГБ не влезают в телефон и требуют склейки перед запуском.
        assertTrue(
            HuggingFaceParser.parseTree(tree).none { it.path.contains("-of-") }
        )
    }

    @Test
    fun `размер берётся из lfs, а не из размера указателя`() {
        val byPath = HuggingFaceParser.parseTree(tree).associateBy { it.path }

        assertEquals(2_497_281_440L, byPath.getValue("Qwen3-4B-Q4_K_M.gguf").sizeBytes)
        assertEquals(1_048_576_000L, byPath.getValue("gemma-3-1b-it-int4.litertlm").sizeBytes)
        assertEquals(
            "9f2c1d4e5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f",
            byPath.getValue("Qwen3-4B-Q4_K_M.gguf").sha256
        )
    }

    @Test
    fun `файл без lfs берёт собственный размер и остаётся без контрольной суммы`() {
        val file = HuggingFaceParser.parseTree(tree).single { it.path == "Qwen3-4B-Q8_0.gguf" }

        assertEquals(4_692_373_504L, file.sizeBytes)
        assertNull("проверять целостность нечем — это должно быть видно", file.sha256)
    }

    @Test
    fun `lfs без oid не подставляет пустую строку`() {
        val withoutOid = """
            [{"type":"file","path":"model-Q4_K_M.gguf","size":135,"lfs":{"size":2000000000,"pointerSize":135}}]
        """.trimIndent()

        val file = HuggingFaceParser.parseTree(withoutOid).single()
        assertEquals(2_000_000_000L, file.sizeBytes)
        assertNull(file.sha256)
    }

    @Test
    fun `расширение распознаётся без учёта регистра`() {
        val upper = """
            [{"type":"file","path":"Model-Q4_K_M.GGUF","size":100},
             {"type":"file","path":"Gemma.LiteRTLM","size":200},
             {"type":"file","path":"Shard-00001-OF-00003.GGUF","size":300}]
        """.trimIndent()

        assertEquals(
            listOf("Model-Q4_K_M.GGUF", "Gemma.LiteRTLM"),
            HuggingFaceParser.parseTree(upper).map { it.path }
        )
    }

    @Test
    fun `битый ответ дерева даёт пустой список`() {
        assertTrue(HuggingFaceParser.parseTree("не json").isEmpty())
        assertTrue(HuggingFaceParser.parseTree("""{"error":"Entry not found"}""").isEmpty())
    }
}
