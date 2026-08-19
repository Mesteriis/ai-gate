package com.aigate.router.download

import com.aigate.router.data.model.LocalModel
import com.aigate.router.gateway.local.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Путь файла модели считается из полей записи каждый раз заново, поэтому
 * правила именования обязаны быть неизменны: стоит им поехать — и скачанные
 * гигабайты перестанут находиться, а качаться будут повторно.
 *
 * Проверяется только чистая часть [ModelStorage]: она не трогает Context и
 * потому не требует эмулятора.
 */
class ModelStorageTest {

    private fun model(source: String, repo: String, ref: String) = LocalModel(
        source = source,
        repo = repo,
        ref = ref,
        engine = EngineKind.GGUF.dbValue,
        displayName = "тестовая модель",
        sizeBytes = 1_000_000,
    )

    @Test
    fun `слэш в имени репозитория не превращается в подкаталог`() {
        // Иначе "unsloth/Qwen3-1.7B-GGUF" развалился бы на два уровня пути.
        assertEquals("unsloth_Qwen3-1.7B-GGUF", ModelStorage.sanitize("unsloth/Qwen3-1.7B-GGUF"))
    }

    @Test
    fun `пробелы и двоеточия заменяются подчёркиванием`() {
        assertEquals("Qwen_3_4B", ModelStorage.sanitize("Qwen 3 4B"))
        assertEquals("qwen3_4b-instruct", ModelStorage.sanitize("qwen3:4b-instruct"))
    }

    @Test
    fun `точка, дефис и подчёркивание остаются как есть`() {
        // На них держатся расширения и принятые в реестрах имена квантований.
        assertEquals("Qwen3-4B-Q4_K_M.gguf", ModelStorage.sanitize("Qwen3-4B-Q4_K_M.gguf"))
    }

    @Test
    fun `кириллица не попадает в имя файла`() {
        // Русские буквы в именах файлов ломают часть нативных загрузчиков
        // моделей, поэтому от них не остаётся ничего, кроме разделителя.
        assertEquals("_", ModelStorage.sanitize("Модель"))
        assertEquals("_1.gguf", ModelStorage.sanitize("модель_1.gguf"))
    }

    @Test
    fun `повторы подчёркиваний схлопываются`() {
        // Без этого "a///b   c" дал бы имя из шести подчёркиваний подряд.
        assertEquals("a_b_c", ModelStorage.sanitize("a///b   c"))
    }

    @Test
    fun `длинное имя обрезается до ста символов`() {
        val long = "q".repeat(250)
        assertEquals(100, ModelStorage.sanitize(long).length)
    }

    @Test
    fun `пустая строка превращается в запасное имя`() {
        assertEquals("model", ModelStorage.sanitize(""))
        assertEquals("model", ModelStorage.sanitize("   "))
    }

    @Test
    fun `имя из одних точек не уводит путь за пределы каталога`() {
        // ".." остался бы допустимым именем и поднял бы файл на уровень выше.
        assertEquals("model", ModelStorage.sanitize(".."))
        assertEquals("model", ModelStorage.sanitize("."))
        assertEquals(".._etc_passwd", ModelStorage.sanitize("../etc/passwd"))
    }

    @Test
    fun `путь модели Ollama собирается из движка, источника, репозитория и тега`() {
        assertEquals(
            "gguf/ollama/qwen3/qwen3-4b-q4_K_M.gguf",
            ModelStorage.relativePathFor("gguf", "ollama", "qwen3", "qwen3-4b-q4_K_M.gguf"),
        )
    }

    @Test
    fun `путь модели HuggingFace складывает репозиторий в один каталог`() {
        assertEquals(
            "gguf/hf/unsloth_Qwen3-1.7B-GGUF/Qwen3-1.7B-Q4_K_M.gguf",
            ModelStorage.relativePathFor(
                "gguf",
                "hf",
                "unsloth/Qwen3-1.7B-GGUF",
                "Qwen3-1.7B-Q4_K_M.gguf",
            ),
        )
    }

    @Test
    fun `в пути ровно четыре уровня при любом мусоре во входных данных`() {
        val path = ModelStorage.relativePathFor("../gguf", "hf/../..", "a/b/c", "../../x.gguf")
        assertEquals(4, path.split("/").size)
        assertTrue("подъёма по дереву быть не должно: $path", path.split("/").none { it == ".." })
    }

    @Test
    fun `у HuggingFace именем файла остаётся ref`() {
        // По нему же собирается ссылка на скачивание, менять его нельзя.
        val hf = model(LocalModel.SOURCE_HF, "unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf")
        assertEquals("Qwen3-1.7B-Q4_K_M.gguf", ModelStorage.fileNameFor(hf))
    }

    @Test
    fun `у Ollama имя файла собирается из репозитория и тега`() {
        // У Ollama файла в реестре нет, есть тег, поэтому имя строим сами.
        val ollama = model(LocalModel.SOURCE_OLLAMA, "qwen3", "4b-instruct-q4_K_M")
        assertEquals("qwen3-4b-instruct-q4_K_M.gguf", ModelStorage.fileNameFor(ollama))
    }

    @Test
    fun `экзотические символы тега Ollama обезврежены в имени файла`() {
        val ollama = model(LocalModel.SOURCE_OLLAMA, "library/qwen3", "4b:latest")
        assertEquals("library_qwen3-4b_latest.gguf", ModelStorage.fileNameFor(ollama))
    }

    @Test
    fun `один и тот же путь получается при повторном вычислении`() {
        // На этом держится докачка и распознавание уже скачанной модели.
        val hf = model(LocalModel.SOURCE_HF, "unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf")
        val first = ModelStorage.relativePathFor(hf.engine, hf.source, hf.repo, ModelStorage.fileNameFor(hf))
        val second = ModelStorage.relativePathFor(hf.engine, hf.source, hf.repo, ModelStorage.fileNameFor(hf))
        assertEquals(first, second)
        assertEquals("gguf/hf/unsloth_Qwen3-1.7B-GGUF/Qwen3-1.7B-Q4_K_M.gguf", first)
    }
}
