package com.aigate.router.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имя файла — единственный источник сведений о локальной модели: ни каталог
 * Hugging Face, ни теги Ollama не отдают квант и число параметров отдельными
 * полями. Разбор проверяется на реальных написаниях имён.
 */
class ModelNameHeuristicsTest {

    /** Таблица квантов продублирована намеренно: правка в коде должна ронять тест. */
    private val allQuants = listOf(
        "Q2_K", "Q3_K_S", "Q3_K_M", "Q3_K_L",
        "Q4_0", "Q4_1", "Q4_K_S", "Q4_K_M",
        "Q5_0", "Q5_1", "Q5_K_S", "Q5_K_M",
        "Q6_K", "Q8_0",
        "IQ1_S", "IQ2_XXS", "IQ2_XS", "IQ2_S", "IQ2_M",
        "IQ3_XXS", "IQ3_XS", "IQ3_S", "IQ3_M", "IQ4_XS", "IQ4_NL",
        "F16", "BF16", "F32",
    )

    @Test
    fun `каждый известный квант читается из имени в любом регистре`() {
        allQuants.forEach { quant ->
            assertEquals(quant, ModelNameHeuristics.parseQuant("model-${quant.lowercase()}.gguf"))
            assertEquals(quant, ModelNameHeuristics.parseQuant("Model.$quant.gguf"))
        }
    }

    @Test
    fun `квант находится во всех формах имени`() {
        assertEquals("Q4_K_M", ModelNameHeuristics.parseQuant("qwen3-4b-q4_K_M.gguf"))
        assertEquals("Q4_K_M", ModelNameHeuristics.parseQuant("4b-q4_K_M"))
        assertEquals("Q4_0", ModelNameHeuristics.parseQuant("qwen3:q4_0"))
        assertEquals("Q5_K_M", ModelNameHeuristics.parseQuant("Model.Q5_K_M.gguf"))
        assertEquals("Q6_K", ModelNameHeuristics.parseQuant("gemma-2-9b-it-Q6_K.gguf"))
        assertEquals("Q4_K_M", ModelNameHeuristics.parseQuant("qwen2.5:1.5b-instruct-q4_K_M"))
    }

    @Test
    fun `длинный квант не разбирается как короткий`() {
        assertEquals("IQ2_XXS", ModelNameHeuristics.parseQuant("model-iq2_xxs.gguf"))
        assertEquals("IQ2_XS", ModelNameHeuristics.parseQuant("model-iq2_xs.gguf"))
        assertEquals("IQ2_S", ModelNameHeuristics.parseQuant("model-iq2_s.gguf"))
        assertEquals("IQ3_XXS", ModelNameHeuristics.parseQuant("model-iq3_xxs.gguf"))
        // bf16 — отдельный формат, а не f16 с приставкой: без границы слева
        // разбор дал бы F16 и ту же оценку по ошибке.
        assertEquals("BF16", ModelNameHeuristics.parseQuant("model-bf16.gguf"))
    }

    @Test
    fun `имя без кванта не даёт ложного срабатывания`() {
        assertNull(ModelNameHeuristics.parseQuant("llama-3.2-3b-instruct.gguf"))
        assertNull(ModelNameHeuristics.parseQuant("gemma3"))
        assertNull(ModelNameHeuristics.parseQuant("gemini-nano"))
        assertNull(ModelNameHeuristics.parseQuant(""))
    }

    @Test
    fun `биты на вес заданы для всей таблицы`() {
        allQuants.forEach { quant ->
            assertTrue("для $quant нет числа бит", ModelNameHeuristics.bitsPerWeight(quant) > 0.0)
        }
        assertEquals(4.85, ModelNameHeuristics.bitsPerWeight("Q4_K_M"), 1e-9)
        assertEquals(4.85, ModelNameHeuristics.bitsPerWeight("q4_k_m"), 1e-9)
        assertEquals(16.0, ModelNameHeuristics.bitsPerWeight("F16"), 1e-9)
        assertEquals(16.0, ModelNameHeuristics.bitsPerWeight("BF16"), 1e-9)
        assertEquals(32.0, ModelNameHeuristics.bitsPerWeight("F32"), 1e-9)
    }

    @Test
    fun `незнакомый квант оценивается осторожно, а не нулём`() {
        // Новые кванты появляются регулярно; отказ считать хуже оценки сверху.
        assertEquals(5.0, ModelNameHeuristics.bitsPerWeight("Q4_K_XL"), 1e-9)
        assertEquals(5.0, ModelNameHeuristics.bitsPerWeight(""), 1e-9)
    }

    @Test
    fun `число параметров читается из разных написаний`() {
        assertEquals(7.0, ModelNameHeuristics.parseParamsB("7b")!!, 1e-9)
        assertEquals(0.6, ModelNameHeuristics.parseParamsB("0.6B")!!, 1e-9)
        assertEquals(235.0, ModelNameHeuristics.parseParamsB("235b")!!, 1e-9)
        assertEquals(1.5, ModelNameHeuristics.parseParamsB("1_5b")!!, 1e-9)
        assertEquals(4.0, ModelNameHeuristics.parseParamsB("qwen3-4b-q4_K_M.gguf")!!, 1e-9)
        assertEquals(1.5, ModelNameHeuristics.parseParamsB("qwen2.5:1.5b-instruct-q4_K_M")!!, 1e-9)
        assertEquals(20.0, ModelNameHeuristics.parseParamsB("gpt-oss-20b")!!, 1e-9)
        assertEquals(7.0, ModelNameHeuristics.parseParamsB("Mistral-7B-Instruct-v0.2-GGUF")!!, 1e-9)
    }

    @Test
    fun `MoE стоит по памяти как все эксперты вместе`() {
        assertEquals(56.0, ModelNameHeuristics.parseParamsB("8x7b")!!, 1e-9)
        assertEquals(
            56.0,
            ModelNameHeuristics.parseParamsB("mixtral-8x7b-instruct-v0.1.Q4_K_M.gguf")!!,
            1e-9,
        )
    }

    @Test
    fun `версия и квант не считаются числом параметров`() {
        assertNull(ModelNameHeuristics.parseParamsB("llama-3.2"))
        assertNull(ModelNameHeuristics.parseParamsB("gemma3"))
        assertNull(ModelNameHeuristics.parseParamsB("q4_0"))
        assertNull(ModelNameHeuristics.parseParamsB("Q4_K_M"))
        assertNull(ModelNameHeuristics.parseParamsB("model-bf16.gguf"))
        assertNull(ModelNameHeuristics.parseParamsB("f32"))
        assertNull(ModelNameHeuristics.parseParamsB(""))
    }

    @Test
    fun `оценка размера файла и обратный пересчёт сходятся`() {
        val size = ModelNameHeuristics.estimateFileSizeBytes(7.0, "Q4_K_M")
        assertTrue(
            "7B в Q4_K_M — это единицы гигабайт, получено $size",
            size in 4_000_000_000..5_500_000_000,
        )
        assertEquals(7.0, ModelNameHeuristics.estimateParamsB(size, "Q4_K_M"), 0.01)

        val tiny = ModelNameHeuristics.estimateFileSizeBytes(0.6, "IQ3_XXS")
        assertEquals(0.6, ModelNameHeuristics.estimateParamsB(tiny, "IQ3_XXS"), 0.01)
    }

    @Test
    fun `оценка по размеру файла не завышает число параметров`() {
        // Реальный 7B Q4_K_M весит около 4,4 ГБ — меньше оценки сверху, поэтому
        // обратный пересчёт возвращает чуть меньше семи, и это правильно:
        // завышенное число параметров раздуло бы прогноз KV-кеша.
        assertEquals(6.55, ModelNameHeuristics.estimateParamsB(4_370_000_000, "Q4_K_M"), 0.05)
    }

    @Test
    fun `более тяжёлый квант даёт больший файл`() {
        val f16 = ModelNameHeuristics.estimateFileSizeBytes(7.0, "F16")
        val q8 = ModelNameHeuristics.estimateFileSizeBytes(7.0, "Q8_0")
        val q4 = ModelNameHeuristics.estimateFileSizeBytes(7.0, "Q4_K_M")
        val iq2 = ModelNameHeuristics.estimateFileSizeBytes(7.0, "IQ2_XXS")
        assertTrue(f16 > q8)
        assertTrue(q8 > q4)
        assertTrue(q4 > iq2)
    }
}
