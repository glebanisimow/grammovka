package app.grammovka.data.ai

/**
 * Who answers the AI requests. [title] is what the UI calls it («Claude читает этикетку…», «Оценка Qwen»).
 * Qwen exists for users where Anthropic is not available (user request 2026-11-20: a user in Belarus).
 */
enum class AiProvider(val title: String, val company: String) {
    ANTHROPIC("Claude", "Anthropic"),
    QWEN("Qwen", "Alibaba Cloud"),
}

/**
 * Alibaba Cloud Model Studio, OpenAI-compatible mode. A workspace key (`sk-ws-…`) works only on its workspace
 * endpoint `https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1`; on the shared
 * `dashscope-intl` domain every model answers 403 `AccessDenied.Unpurchased` (checked on a real key 2026-11-20).
 * Docs: https://www.alibabacloud.com/help/en/model-studio/regions , https://www.alibabacloud.com/help/en/model-studio/base-url
 */
object QwenEndpoint {
    /** Shared Singapore endpoint: works for plain `sk-` keys of the default workspace. */
    const val SHARED = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    private const val REGION_HOST = "ap-southeast-1.maas.aliyuncs.com"
    private const val PATH = "/compatible-mode/v1"

    /**
     * What the user typed → base URL ending with `/compatible-mode/v1`, or null if it cannot be one.
     * Accepts a workspace id (`ws-…`, `llm-…`), a host, a base URL or a full `…/chat/completions` URL; blank → [SHARED].
     */
    fun normalize(input: String): String? {
        val s = input.trim().trimEnd('/')
        if (s.isEmpty()) return SHARED
        if (Regex("^(ws|llm)-[a-z0-9]+$", RegexOption.IGNORE_CASE).matches(s)) return "https://${s.lowercase()}.$REGION_HOST$PATH"
        val noScheme = s.removePrefix("https://").removePrefix("http://")
        val host = noScheme.substringBefore('/')
        if (!host.contains('.') || host.contains(' ')) return null
        return "https://$host$PATH"
    }

    /** The workspace id in a workspace endpoint, for display; null for the shared endpoint. */
    fun workspaceOf(baseUrl: String): String? =
        baseUrl.removePrefix("https://").substringBefore('/').takeIf { it.endsWith(REGION_HOST) }?.substringBefore('.')
}

/**
 * The active provider for UI texts («Qwen читает этикетку…»). [app.grammovka.di.AppContainer] keeps it in sync with
 * the settings; texts read it when they are built, which is enough: the provider changes only in settings.
 */
object ActiveAi {
    val provider = kotlinx.coroutines.flow.MutableStateFlow(AiProvider.ANTHROPIC)
    val title: String get() = provider.value.title
}
