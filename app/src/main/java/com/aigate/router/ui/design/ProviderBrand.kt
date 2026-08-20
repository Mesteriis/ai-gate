package com.aigate.router.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aigate.router.R

/**
 * Опознавательный знак провайдера.
 *
 * Для известных поставщиков это их логотип (векторные файлы из набора Simple
 * Icons, лицензия CC0; сами знаки остаются товарными знаками владельцев и
 * используются только для обозначения их сервисов). Для незнакомого провайдера
 * — монограмма из первой буквы имени, чтобы список всё равно читался с одного
 * взгляда.
 */
data class ProviderBrand(
    /** Логотип, если он есть в наборе. */
    @DrawableRes val logo: Int?,
    /** Подложка знака: фирменный цвет бренда. */
    val color: Color,
    /** Запасная монограмма, когда логотипа нет. */
    val monogram: String,
)

/**
 * Сопоставление по имени и типу провайдера. Codex и ChatGPT — это OpenAI,
 * поэтому у них общий знак; Claude Code — Anthropic; локальные модели — Ollama.
 * Порядок важен: более узкие правила идут первыми.
 */
private val brands: List<Pair<Regex, ProviderBrand>> = listOf(
    rule("codex|chatgpt", R.drawable.logo_openai, 0xFF10A37F, "Cx"),
    rule("openai|gpt", R.drawable.logo_openai, 0xFF412991, "AI"),
    rule("claude", R.drawable.logo_claude, 0xFFD97757, "Cl"),
    rule("anthropic", R.drawable.logo_anthropic, 0xFF191919, "An"),
    rule("gemini|google", R.drawable.logo_gemini, 0xFF8E75B2, "Gm"),
    rule("deepseek", R.drawable.logo_deepseek, 0xFF5786FE, "Ds"),
    rule("qwen|dashscope|тунъи", R.drawable.logo_qwen, 0xFF6950EF, "Qw"),
    rule("alibaba|aliyun", R.drawable.logo_alibaba, 0xFFFF6A00, "Ab"),
    rule("openrouter", R.drawable.logo_openrouter, 0xFF64748B, "Or"),
    rule("mistral", R.drawable.logo_mistral, 0xFFFA520F, "Mi"),
    rule("perplexity", R.drawable.logo_perplexity, 0xFF1FB8CD, "Px"),
    rule("ollama|llama\\.cpp|lmstudio|local|локал", R.drawable.logo_ollama, 0xFF1F2937, "Ol"),
    // Логотипов нет в наборе — остаётся монограмма в фирменном цвете.
    rule("cursor", null, 0xFF0B0B0B, "Cu"),
    rule("groq", null, 0xFFF55036, "Gq"),
    rule("together", null, 0xFF0F6FFF, "Tg"),
    rule("cohere", null, 0xFF39594D, "Co"),
)

private fun rule(pattern: String, @DrawableRes logo: Int?, color: Long, monogram: String) =
    Regex(pattern, RegexOption.IGNORE_CASE) to ProviderBrand(logo, Color(color), monogram)

/**
 * Подобрать знак по имени и типу провайдера.
 *
 * Имя проверяется ПЕРВЫМ, потому что тип — это протокол, а не бренд: провайдер
 * DeepSeek имеет тип «OpenAI Compatible» и без этого правила получал знак
 * OpenAI. Типы вида «… Compatible» для подбора знака не используются совсем.
 */
fun providerBrand(name: String, type: String = ""): ProviderBrand {
    brands.firstOrNull { (pattern, _) -> pattern.containsMatchIn(name) }?.let { return it.second }
    // «… Compatible» — это название протокола целиком, а не бренд: у такого типа
    // знак не берём вообще, иначе любой OpenAI-совместимый сервис выглядел бы
    // как сам OpenAI.
    val isProtocolName = type.contains("compatible", ignoreCase = true)
    if (!isProtocolName && type.isNotBlank()) {
        brands.firstOrNull { (pattern, _) -> pattern.containsMatchIn(type) }
            ?.let { return it.second }
    }
    return ProviderBrand(
        logo = null,
        color = Color(0xFF64748B),
        monogram = name.trim().firstOrNull()?.uppercase() ?: "?",
    )
}

/**
 * Квадратный знак провайдера для списков и карточек: логотип на светлой
 * подложке фирменного цвета, либо монограмма на плотной заливке.
 */
@Composable
fun ProviderAvatar(
    name: String,
    type: String = "",
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    val brand = providerBrand(name, type)
    val shape = RoundedCornerShape(size / 3.6f)
    // Знаки брендов монохромные и часто почти чёрные (Anthropic #191919,
    // Ollama #1F2937) — на тёмном фоне они исчезали. Оттенок сохраняем,
    // а яркость поднимаем ровно настолько, чтобы знак читался.
    val visible = brand.color.readableOn(MaterialTheme.colorScheme.surface)
    if (brand.logo != null) {
        Box(
            modifier = modifier
                .size(size)
                // Подложка приглушена, чтобы логотип читался и в тёмной теме.
                .background(visible.copy(alpha = 0.12f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(brand.logo),
                contentDescription = null,
                colorFilter = ColorFilter.tint(visible),
                modifier = Modifier.size(size).padding(size / 4.5f),
            )
        }
        return
    }
    Box(
        modifier = modifier
            .size(size)
            .background(visible, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = brand.monogram,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.36f).sp,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Фирменный цвет, поднятый до различимого на данной поверхности: тон бренда
 * сохраняется, меняется только светлота. Нужен потому, что фирменные цвета —
 * не токены темы и не обязаны контрастировать с нашим фоном.
 */
private fun Color.readableOn(surface: Color): Color {
    val dark = surface.luminance() < 0.5f
    // Порог подобран по самым тёмным знакам набора (#191919, #0B0B0B).
    return when {
        dark && luminance() < 0.16f -> lerp(this, Color.White, 0.62f)
        !dark && luminance() > 0.82f -> lerp(this, Color.Black, 0.35f)
        else -> this
    }
}
