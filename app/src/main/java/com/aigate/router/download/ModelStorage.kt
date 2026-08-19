package com.aigate.router.download

import android.content.Context
import android.os.StatFs
import com.aigate.router.data.model.LocalModel
import java.io.File

/**
 * Раскладка файлов локальных моделей на диске.
 *
 * Веса моделей — это гигабайты, которые живут дольше любой записи в базе,
 * поэтому имя файла нельзя выбирать в момент скачивания: одно и то же
 * сочетание источника, репозитория и тега обязано давать один и тот же путь и
 * после перезапуска, и после переустановки записи. Отсюда чистые функции
 * [sanitize], [relativePathFor] и [fileNameFor] — путь считается из полей
 * записи, а не хранится где-то ещё.
 *
 * Работа с [Context] сосредоточена в нескольких тонких функциях, вся логика
 * имён свободна от Android и проверяется обычным JVM-тестом.
 */
object ModelStorage {

    /** Каталог с весами внутри песочницы приложения. */
    private const val MODELS_DIR = "models"

    /**
     * Недокачанные файлы лежат отдельно от готовых: так обход готовых моделей
     * не спотыкается об обрывки, а уборка мусора различает «файл модели» и
     * «остаток прерванной загрузки» по каталогу, а не по имени.
     */
    private const val PARTIAL_DIR = ".partial"

    private const val PARTIAL_SUFFIX = ".part"

    /**
     * Потолок длины имени. Ограничение файловых систем Android — 255 байт на
     * элемент пути, но имена репозиториев бывают длиннее осмысленного, а к
     * имени ещё добавляются тег и расширение, поэтому запас взят с избытком.
     */
    private const val MAX_NAME_LENGTH = 100

    /** Имя для случая, когда от исходной строки не осталось ничего пригодного. */
    private const val FALLBACK_NAME = "model"

    /** Символы, которые файловая система принимает без сюрпризов. */
    private val FORBIDDEN = Regex("[^A-Za-z0-9._-]")

    private val UNDERSCORES = Regex("_+")

    /**
     * Корень хранилища моделей.
     *
     * getExternalFilesDir выбран сознательно: он не требует ни одного
     * разрешения, система сама вычищает каталог при удалении приложения, и
     * гигабайтные веса не занимают внутренний раздел, на котором у телефона
     * обычно и заканчивается место.
     *
     * Внешнее хранилище может быть не смонтировано (карта вынута, устройство
     * отдано по MTP) — тогда getExternalFilesDir возвращает null, и мы уходим
     * во внутреннюю память: лучше скачать модель туда, чем отказать совсем.
     */
    fun root(context: Context): File {
        val base = try {
            context.getExternalFilesDir(null)
        } catch (_: Throwable) {
            null
        } ?: context.filesDir
        val dir = File(base, MODELS_DIR)
        if (!dir.isDirectory) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Приведение произвольной строки к имени элемента пути.
     *
     * Нужна потому, что repo вида "unsloth/Qwen3-1.7B-GGUF" содержит слэш и
     * каталогом стать не может, а теги Ollama приходят с двоеточием.
     *
     * Имя из одних точек ("." или "..") превращается в [FALLBACK_NAME]: точка
     * сама по себе разрешена и нужна расширениям, но ".." увёл бы собранный
     * путь за пределы корня хранилища.
     */
    fun sanitize(name: String): String {
        val cleaned = name.trim()
            .replace(FORBIDDEN, "_")
            .replace(UNDERSCORES, "_")
            .take(MAX_NAME_LENGTH)
        return if (cleaned.isEmpty() || cleaned.all { it == '.' }) FALLBACK_NAME else cleaned
    }

    /**
     * Путь файла модели относительно [root].
     *
     * Движок и источник стоят первыми уровнями, чтобы одну и ту же модель,
     * скачанную из разных мест или под разный движок, не пришлось различать по
     * имени файла. Все четыре части проходят [sanitize], включая движок и
     * источник: они приходят из базы, где строку мог написать пользователь при
     * ручном добавлении, и на допустимых значениях ("gguf", "ollama") санация
     * ничего не меняет.
     */
    fun relativePathFor(engine: String, source: String, repo: String, fileName: String): String =
        "${sanitize(engine)}/${sanitize(source)}/${sanitize(repo)}/${sanitize(fileName)}"

    /**
     * Имя файла модели.
     *
     * У HuggingFace ref — это и есть имя файла в репозитории, менять его нельзя:
     * по нему собирается ссылка на скачивание, и по нему же файл узнаётся при
     * повторном обращении. У Ollama файла как такового нет, есть тег, поэтому
     * имя собирается из репозитория и тега; расширение gguf ставится потому,
     * что других форматов реестр Ollama не отдаёт.
     */
    fun fileNameFor(model: LocalModel): String =
        if (model.source.equals(LocalModel.SOURCE_HF, ignoreCase = true)) {
            model.ref
        } else {
            "${sanitize(model.repo)}-${sanitize(model.ref)}.gguf"
        }

    /**
     * Готовый файл модели: путь однозначно определяется полями записи.
     *
     * Каталог намеренно не создаётся — путь спрашивают и для проверки «а не
     * скачано ли уже», и плодить пустые папки под каждую строку каталога
     * незачем. Родителя создаёт тот, кто пишет файл.
     */
    fun finalFileFor(context: Context, model: LocalModel): File = File(
        root(context),
        relativePathFor(model.engine, model.source, model.repo, fileNameFor(model)),
    )

    /**
     * Файл незавершённой загрузки. Имя строится по идентификатору записи, а не
     * по имени модели: докачка должна находить свой кусок даже после того, как
     * пользователь переименовал модель или сменил тег.
     */
    fun partialFileFor(context: Context, id: Long): File {
        val dir = File(root(context), PARTIAL_DIR)
        if (!dir.isDirectory) {
            dir.mkdirs()
        }
        return File(dir, "$id$PARTIAL_SUFFIX")
    }

    /** Свободное место на разделе, где лежит хранилище моделей. */
    fun freeBytes(context: Context): Long = try {
        StatFs(root(context).absolutePath).availableBytes
    } catch (_: Throwable) {
        // Раздел мог отвалиться вместе с картой памяти. Ноль честнее выдуманного
        // объёма: гейт откажет в скачивании, а не начнёт качать в никуда.
        0L
    }

    /**
     * Сколько места занято моделями. Недокачанные .part считаются наравне с
     * готовыми файлами: место они занимают настоящее, и пользователь должен
     * видеть реальную занятость, а не только сумму завершённых загрузок.
     */
    fun usedBytes(context: Context): Long = try {
        root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Throwable) {
        0L
    }

    /**
     * Удаление файлов одной модели.
     *
     * Кроме вычисленного пути удаляется и путь из [LocalModel.filePath]: правила
     * именования могли поменяться между версиями приложения, и тогда на диске
     * лежит файл со старым именем, на который ссылается только запись. Чужие
     * пути при этом не трогаются — удаляется лишь то, что лежит внутри [root].
     */
    fun deleteModelFiles(context: Context, model: LocalModel) {
        val root = root(context)
        val targets = buildList {
            add(finalFileFor(context, model))
            if (model.filePath.isNotBlank()) add(File(model.filePath))
            add(partialFileFor(context, model.id))
        }
        targets.forEach { deleteInside(root, it) }
    }

    /**
     * Уборка файлов, на которые никто не ссылается.
     *
     * Без неё сбой при скачивании или удаление записи в обход [deleteModelFiles]
     * оставляли бы гигабайты навсегда: увидеть их в песочнице приложения
     * пользователю нечем, а место они занимают.
     *
     * @param knownPaths пути файлов готовых записей
     * @param activeIds идентификаторы записей, чья загрузка ещё может продолжиться
     * @return сколько файлов удалено
     */
    fun cleanupOrphans(context: Context, knownPaths: Set<String>, activeIds: Set<Long>): Int {
        val root = root(context)
        val partialDir = File(root, PARTIAL_DIR)
        // Пути сравниваются приведёнными к абсолютным: в базу они попадают из
        // разных мест и могут отличаться формой записи, а не файлом.
        val known = knownPaths.mapNotNullTo(mutableSetOf()) { path ->
            path.takeIf { it.isNotBlank() }?.let { File(it).absolutePath }
        }
        var removed = 0
        try {
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val orphan = if (file.parentFile?.absolutePath == partialDir.absolutePath) {
                    // Остаток загрузки жив, пока жива запись, которая его качает.
                    val id = file.name.removeSuffix(PARTIAL_SUFFIX).toLongOrNull()
                    id == null || id !in activeIds
                } else {
                    file.absolutePath !in known
                }
                if (orphan && file.delete()) {
                    removed++
                }
            }
            pruneEmptyDirs(root)
        } catch (_: Throwable) {
            // Обход мог оборваться на отмонтированном разделе: сколько успели
            // убрать, столько и сообщаем — уборка повторится в следующий раз.
        }
        return removed
    }

    /**
     * Удаление файла с проверкой, что он лежит внутри хранилища. Путь может
     * прийти из базы, а стирать что-то за пределами своей песочницы приложение
     * не должно ни при какой порче данных.
     */
    private fun deleteInside(root: File, file: File) {
        try {
            // Сравниваются именно канонические пути: внешнее хранилище Android
            // видно через символические ссылки, и обычное сравнение строк
            // приняло бы один и тот же файл за чужой.
            val rootPrefix = root.canonicalPath + File.separator
            val target = file.canonicalFile
            if (!target.path.startsWith(rootPrefix)) return
            if (target.isFile) {
                target.delete()
            }
            pruneEmptyParents(rootPrefix, target.parentFile)
        } catch (_: Throwable) {
            // Недоступный путь означает, что удалять нечего.
        }
    }

    /**
     * Подчистка пустых каталогов вверх до корня: иначе после удаления моделей
     * остаётся дерево пустых папок источников и репозиториев.
     *
     * Подъём ограничен префиксом корня: за его пределами лежат каталоги
     * приложения, которые удалять нельзя ни при какой форме пути.
     */
    private fun pruneEmptyParents(rootPrefix: String, from: File?) {
        var dir: File? = from
        while (true) {
            val current = dir ?: return
            if (!current.path.startsWith(rootPrefix) || !current.isDirectory) return
            // Непустой каталог и неудавшееся удаление одинаково означают «выше не идём».
            if (current.list()?.isEmpty() != true || !current.delete()) return
            dir = current.parentFile
        }
    }

    /** То же самое после массовой уборки, но обходом сверху вниз. */
    private fun pruneEmptyDirs(root: File) {
        root.walkBottomUp()
            .filter { it.isDirectory && it.absolutePath != root.absolutePath }
            .forEach { dir -> if (dir.list()?.isEmpty() == true) dir.delete() }
    }
}
