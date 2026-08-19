package com.aigate.router.catalog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог Ollama лежит в ассете, а не приходит по сети, поэтому единственная
 * защита от опечатки в JSON — тест, который разбирает настоящий файл.
 * Он же фиксирует порядок выдачи поиска: пользователь помнит начало имени.
 */
class CuratedOllamaCatalogTest {

    /**
     * Рабочая директория JVM-теста — модуль `app/`, но при запуске из корня
     * проекта путь другой, и падать из-за этого тест не должен.
     */
    private fun catalogFile(): File = listOf(
        File("src/main/assets/ollama_catalog.json"),
        File("app/src/main/assets/ollama_catalog.json"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("не найден ассет ollama_catalog.json, рабочая директория ${File(".").absolutePath}")

    private fun realCatalog(): List<CuratedOllamaCatalog.CuratedModel> =
        CuratedOllamaCatalog.parse(catalogFile().readText())

    private fun model(name: String, description: String = "") =
        CuratedOllamaCatalog.CuratedModel(name, description, emptyList())

    @Test
    fun `реальный ассет разбирается и содержит семейства для телефона`() {
        val models = realCatalog()
        assertTrue("каталог не разобрался: ${models.size} записей", models.size >= 10)
        assertTrue(models.any { it.name == "qwen3" })
        assertTrue(models.any { it.name == "llama3.2" })
    }

    @Test
    fun `каждая запись ассета пригодна для показа и установки`() {
        realCatalog().forEach { m ->
            assertTrue("пустое имя в каталоге", m.name.isNotBlank())
            assertTrue("нет описания у ${m.name}", m.description.isNotBlank())
            assertTrue("нет ни одного тега у ${m.name}", m.tags.isNotEmpty())
            m.tags.forEach { t ->
                assertTrue("пустой тег у ${m.name}", t.tag.isNotBlank())
                assertTrue("нет кванта у ${m.name}:${t.tag}", t.quant.isNotBlank())
                // Оценка размера нужна до скачивания: без неё нечего сравнивать
                // со свободной памятью устройства.
                assertTrue("нулевой размер у ${m.name}:${t.tag}", t.approxSizeBytes > 0L)
            }
        }
    }

    @Test
    fun `отсутствующее число параметров остаётся неизвестным`() {
        val tag = CuratedOllamaCatalog.parse(
            """{"models":[{"name":"m","description":"d",
                 "tags":[{"tag":"latest","quant":"F16","approxSizeBytes":100}]}]}"""
        ).single().tags.single()

        assertNull("ноль параметров — не то же самое, что неизвестно", tag.paramsB)
        assertEquals(100L, tag.approxSizeBytes)
    }

    @Test
    fun `поиск находит по подстроке имени и описания`() {
        val models = listOf(
            model("qwen3", "универсальная линейка"),
            model("tinyllama", "минимальные требования к памяти"),
            model("codegemma", "дополнение кода"),
        )

        assertEquals(listOf("qwen3"), CuratedOllamaCatalog.search(models, "wen").map { it.name })
        assertEquals(listOf("codegemma"), CuratedOllamaCatalog.search(models, "кода").map { it.name })
        assertTrue(CuratedOllamaCatalog.search(models, "нет такой модели").isEmpty())
    }

    @Test
    fun `регистр запроса не влияет на результат`() {
        val models = listOf(model("qwen3", "линейка Qwen"))
        assertEquals(1, CuratedOllamaCatalog.search(models, "QWEN").size)
    }

    @Test
    fun `совпадения в начале имени идут первыми`() {
        val models = listOf(
            model("deepseek-r1", "альтернатива qwen для рассуждений"),
            model("qwen3", "универсальная линейка"),
            model("qwen2.5-coder", "генерация кода"),
        )

        // Сначала имена на «qwen» по алфавиту, потом упоминание в описании.
        assertEquals(
            listOf("qwen2.5-coder", "qwen3", "deepseek-r1"),
            CuratedOllamaCatalog.search(models, "qwen").map { it.name },
        )
    }

    @Test
    fun `пустой запрос возвращает весь список`() {
        val models = realCatalog()
        assertEquals(models, CuratedOllamaCatalog.search(models, ""))
        assertEquals(models, CuratedOllamaCatalog.search(models, "   "))
    }

    @Test
    fun `битый JSON даёт пустой список, а не падение`() {
        assertTrue(CuratedOllamaCatalog.parse("не json").isEmpty())
        assertTrue(CuratedOllamaCatalog.parse("").isEmpty())
        assertTrue(CuratedOllamaCatalog.parse("""{"version":1}""").isEmpty())
        assertTrue(CuratedOllamaCatalog.parse("""[1,2,3]""").isEmpty())
    }

    @Test
    fun `записи без имени пропускаются, а не ломают разбор`() {
        val models = CuratedOllamaCatalog.parse(
            """{"models":[{"description":"без имени"},
                          {"name":"gemma3","description":"есть имя","tags":[]}]}"""
        )

        assertEquals(listOf("gemma3"), models.map { it.name })
    }
}
