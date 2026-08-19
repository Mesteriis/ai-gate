// Мост между Kotlin и llama.cpp.
//
// Наружу торчит ровно то, что нужно шлюзу: загрузить модель, применить её
// собственный шаблон диалога, выдать ответ по токенам и уметь остановиться на
// середине. Всё остальное — история диалога, форматирование, учёт расхода —
// живёт в Kotlin, где это проверяется обычными тестами.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "llama-bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

/**
 * Загруженная модель вместе с её контекстом.
 *
 * Флаг отмены — атомарный и живёт здесь, а не в Kotlin: цикл декодирования
 * крутится в нативном коде, и снаружи его иначе не остановить.
 */
struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_sampler *sampler = nullptr;
    std::atomic<bool> cancelled{false};
    /**
     * Хвост незавершённой многобайтовой последовательности.
     *
     * Токенизатор режет текст по токенам, а не по символам, и один символ
     * кириллицы или эмодзи запросто приходит двумя токенами. Отдать такой
     * обрубок в JNI нельзя: NewStringUTF считает это ошибкой приложения и
     * убивает процесс — именно так падал шлюз на третьем запросе.
     */
    std::string pending;
    int prompt_tokens = 0;
    int decoded_tokens = 0;
};

Session *as_session(jlong handle) { return reinterpret_cast<Session *>(handle); }

std::string to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}


/**
 * Длина наибольшего префикса, который целиком состоит из корректных символов
 * UTF-8. Хвост-обрубок остаётся вызывающему, чтобы дождаться продолжения.
 */
size_t valid_utf8_prefix(const std::string &text) {
    size_t i = 0;
    while (i < text.size()) {
        const unsigned char c = static_cast<unsigned char>(text[i]);
        size_t len;
        if (c < 0x80) len = 1;
        else if ((c >> 5) == 0x6) len = 2;
        else if ((c >> 4) == 0xE) len = 3;
        else if ((c >> 3) == 0x1E) len = 4;
        else return i; // Байт продолжения без начала: дальше доверять нечему.
        if (i + len > text.size()) return i;
        for (size_t k = 1; k < len; ++k) {
            if ((static_cast<unsigned char>(text[i + k]) >> 6) != 0x2) return i;
        }
        i += len;
    }
    return i;
}
} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_aigate_llamacpp_LlamaBridge_nativeInit(JNIEnv *, jobject) {
    // Своё логирование библиотеки глушим: она пишет очень много, а нам важны
    // только свои сообщения о загрузке и ошибках.
    llama_log_set([](ggml_log_level level, const char *text, void *) {
        // Ровно ERROR, без CONT: уровень «продолжение» больше ERROR по
        // значению, и на нём библиотека сыплет точками прогресса загрузки —
        // сравнение «не меньше» затопило бы журнал ими.
        if (level == GGML_LOG_LEVEL_ERROR) LOGW("%s", text);
    }, nullptr);
    llama_backend_init();
}

/**
 * Загрузка модели. Возвращает указатель на сессию или 0 при неудаче.
 *
 */
JNIEXPORT jlong JNICALL
Java_com_aigate_llamacpp_LlamaBridge_nativeLoad(
    JNIEnv *env, jobject, jstring path, jint n_ctx, jint n_threads) {
    const std::string model_path = to_string(env, path);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // Только процессор: ускорители подключим отдельно.
    // Файл модели читается страницами по мере надобности: система сама
    // вытесняет неиспользуемые куски вместо того, чтобы держать несколько
    // гигабайт в куче приложения.
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model *model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        LOGW("Модель не загрузилась: %s", model_path.c_str());
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(n_ctx);
    ctx_params.n_batch = static_cast<uint32_t>(n_ctx < 512 ? n_ctx : 512);
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGW("Контекст не создан");
        llama_model_free(model);
        return 0;
    }

    auto *session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);

    // Набор по умолчанию: срез хвоста распределения плюс температура. Жадный
    // выбор давал бы одинаковые ответы на один вопрос, что для чата хуже.
    auto sparams = llama_sampler_chain_default_params();
    session->sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("Модель загружена: %s", model_path.c_str());
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_aigate_llamacpp_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    Session *session = as_session(handle);
    if (session == nullptr) return;
    if (session->sampler) llama_sampler_free(session->sampler);
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}

/**
 * Применение шаблона диалога, зашитого в саму модель.
 *
 * Своей склейкой ролей обойтись нельзя: у каждой модели свой формат, и чужой
 * заставляет её продолжать текст вместо ответа.
 */
JNIEXPORT jstring JNICALL
Java_com_aigate_llamacpp_LlamaBridge_nativeFormatChat(
    JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray texts) {
    Session *session = as_session(handle);
    if (session == nullptr) return env->NewStringUTF("");

    const jsize count = env->GetArrayLength(roles);
    std::vector<std::string> role_store(count);
    std::vector<std::string> text_store(count);
    std::vector<llama_chat_message> messages(count);
    for (jsize i = 0; i < count; ++i) {
        role_store[i] = to_string(env, (jstring) env->GetObjectArrayElement(roles, i));
        text_store[i] = to_string(env, (jstring) env->GetObjectArrayElement(texts, i));
        messages[i] = {role_store[i].c_str(), text_store[i].c_str()};
    }

    const char *tmpl = llama_model_chat_template(session->model, nullptr);
    std::vector<char> buffer(8192);
    int32_t written = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), true, buffer.data(), (int32_t) buffer.size());
    if (written > (int32_t) buffer.size()) {
        buffer.resize(written);
        written = llama_chat_apply_template(
            tmpl, messages.data(), messages.size(), true, buffer.data(), (int32_t) buffer.size());
    }
    if (written < 0) {
        // Шаблона в модели нет — вызывающий соберёт запрос сам.
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(std::string(buffer.data(), written).c_str());
}

/** Разбор запроса на токены и их обработка. Возвращает число токенов запроса. */
JNIEXPORT jint JNICALL
Java_com_aigate_llamacpp_LlamaBridge_nativeStart(
    JNIEnv *env, jobject, jlong handle, jstring prompt) {
    Session *session = as_session(handle);
    if (session == nullptr) return -1;

    session->cancelled = false;
    // Хвост от прошлого ответа обязан уйти: иначе он приклеится к началу
    // нового и даст мусорный первый символ.
    session->pending.clear();
    session->decoded_tokens = 0;
    llama_memory_clear(llama_get_memory(session->ctx), true);

    const std::string text = to_string(env, prompt);
    const int n_prompt = -llama_tokenize(
        session->vocab, text.c_str(), text.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(session->vocab, text.c_str(), text.size(),
                       tokens.data(), tokens.size(), true, true) < 0) {
        return -1;
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(session->ctx, batch) != 0) {
        LOGW("Не удалось обработать запрос");
        return -1;
    }
    session->prompt_tokens = (int) tokens.size();
    return session->prompt_tokens;
}

/**
 * Следующий кусок ответа или пустая строка, когда ответ закончен.
 *
 * Отмену проверяем на каждом шаге: брошенный клиентом счёт обязан прекратиться,
 * иначе телефон греется впустую до самого конца ответа.
 */
JNIEXPORT jstring JNICALL
Java_com_aigate_llamacpp_LlamaBridge_nativeNext(JNIEnv *env, jobject, jlong handle) {
    Session *session = as_session(handle);
    if (session == nullptr || session->cancelled) return env->NewStringUTF("");

    // Крутимся, пока не наберётся хотя бы один целый символ. Пустая строка
    // наверху означает «ответ закончен», поэтому отдать недобранный обрубок
    // нельзя: вызывающий принял бы его за конец.
    while (true) {
        const llama_token token = llama_sampler_sample(session->sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) return env->NewStringUTF("");

        char piece[256];
        const int written = llama_token_to_piece(session->vocab, token, piece, sizeof(piece), 0, true);
        if (written > 0) session->pending.append(piece, written);

        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
        if (llama_decode(session->ctx, batch) != 0) return env->NewStringUTF("");
        session->decoded_tokens++;

        const size_t good = valid_utf8_prefix(session->pending);
        if (good > 0) {
            const std::string out = session->pending.substr(0, good);
            session->pending.erase(0, good);
            return env->NewStringUTF(out.c_str());
        }
        if (session->cancelled) return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL
Java_com_aigate_llamacpp_LlamaBridge_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Session *session = as_session(handle);
    if (session != nullptr) session->cancelled = true;
}

JNIEXPORT jint JNICALL
Java_com_aigate_llamacpp_LlamaBridge_nativeDecodedTokens(JNIEnv *, jobject, jlong handle) {
    Session *session = as_session(handle);
    return session == nullptr ? 0 : session->decoded_tokens;
}

} // extern "C"
