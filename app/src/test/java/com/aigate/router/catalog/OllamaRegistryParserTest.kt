package com.aigate.router.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт разбора манифестов registry.ollama.ai. Фикстуры повторяют реальные
 * ответы реестра: слой весов лежит среди слоёв шаблона, лицензии и параметров.
 */
class OllamaRegistryParserTest {

    private val manifest = """
        {
          "schemaVersion": 2,
          "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
          "config": {
            "mediaType": "application/vnd.docker.container.image.v1+json",
            "digest": "sha256:31df23ea7daa4f2a4b0f92d47b30cbc4c9b4b25e0a2e2fca8b7f7b0a8f5a3c11",
            "size": 487
          },
          "layers": [
            {"mediaType":"application/vnd.ollama.image.model",
             "digest":"sha256:163553aea1b1de62de7c5eb2ee5f0d3b6f0f2b2a6e1a9a1a5d4c3b2a1908f7e6",
             "size":2497281440},
            {"mediaType":"application/vnd.ollama.image.template",
             "digest":"sha256:aaa1111111111111111111111111111111111111111111111111111111111111",
             "size":1482},
            {"mediaType":"application/vnd.ollama.image.license",
             "digest":"sha256:bbb2222222222222222222222222222222222222222222222222222222222222",
             "size":11343},
            {"mediaType":"application/vnd.ollama.image.params",
             "digest":"sha256:ccc3333333333333333333333333333333333333333333333333333333333333",
             "size":102}
          ]
        }
    """.trimIndent()

    @Test
    fun `размер весов и размер загрузки считаются раздельно`() {
        val parsed = OllamaRegistryParser.parseManifest(manifest)

        assertNotNull(parsed)
        assertEquals(
            "sha256:163553aea1b1de62de7c5eb2ee5f0d3b6f0f2b2a6e1a9a1a5d4c3b2a1908f7e6",
            parsed!!.modelDigest
        )
        // В RAM поднимается только слой весов.
        assertEquals(2_497_281_440L, parsed.modelSizeBytes)
        // На диск ложатся все слои: 2497281440 + 1482 + 11343 + 102.
        assertEquals(2_497_294_367L, parsed.totalSizeBytes)
        assertEquals(
            "sha256:31df23ea7daa4f2a4b0f92d47b30cbc4c9b4b25e0a2e2fca8b7f7b0a8f5a3c11",
            parsed.configDigest
        )
    }

    @Test
    fun `манифест без слоя весов не разбирается`() {
        val withoutModel = """
            {"schemaVersion":2,
             "layers":[{"mediaType":"application/vnd.ollama.image.license","digest":"sha256:aaa","size":11343},
                       {"mediaType":"application/vnd.ollama.image.template","digest":"sha256:bbb","size":1482}]}
        """.trimIndent()

        assertNull("качать шаблон без весов бессмысленно", OllamaRegistryParser.parseManifest(withoutModel))
    }

    @Test
    fun `манифест без слоёв не разбирается`() {
        assertNull(OllamaRegistryParser.parseManifest("""{"schemaVersion":2,"config":{"digest":"sha256:aaa"}}"""))
    }

    @Test
    fun `битый манифест не роняет разбор`() {
        assertNull(OllamaRegistryParser.parseManifest("не json"))
        assertNull(OllamaRegistryParser.parseManifest(""))
        assertNull(OllamaRegistryParser.parseManifest("""{"error":"model not found"""))
    }

    @Test
    fun `манифест без config отдаёт null вместо пустого дайджеста`() {
        val noConfig = """
            {"schemaVersion":2,
             "layers":[{"mediaType":"application/vnd.ollama.image.model","digest":"sha256:aaa","size":100}]}
        """.trimIndent()

        val parsed = OllamaRegistryParser.parseManifest(noConfig)!!
        assertNull(parsed.configDigest)
        assertEquals(100L, parsed.totalSizeBytes)
    }

    @Test
    fun `список тегов разбирается в порядке ответа`() {
        val tags = """{"name":"library/qwen3","tags":["latest","4b","8b","30b-a3b"]}"""

        assertEquals(listOf("latest", "4b", "8b", "30b-a3b"), OllamaRegistryParser.parseTagsList(tags))
    }

    @Test
    fun `битый или чужой ответ тегов даёт пустой список`() {
        assertTrue(OllamaRegistryParser.parseTagsList("не json").isEmpty())
        assertTrue(OllamaRegistryParser.parseTagsList("""{"name":"library/qwen3"}""").isEmpty())
        assertTrue(OllamaRegistryParser.parseTagsList("""{"errors":[{"code":"NAME_UNKNOWN"}]}""").isEmpty())
    }

    @Test
    fun `нестроковые элементы тегов пропускаются`() {
        val tags = """{"name":"library/x","tags":["latest",42,null,{"tag":"7b"},"  ","1b"]}"""

        assertEquals(listOf("latest", "1b"), OllamaRegistryParser.parseTagsList(tags))
    }
}
