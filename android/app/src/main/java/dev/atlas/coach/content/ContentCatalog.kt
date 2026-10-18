package dev.atlas.coach.content

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.UUID

enum class ContentKind { VIDEO_LINK, SEARCH }

data class ContentItem(
    val id: String,
    val title: String,
    val url: String,
    val kind: ContentKind = ContentKind.VIDEO_LINK,
    val pinned: Boolean = false,
    val openCount: Int = 0,
    val openCountingEnabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "L'identifiant du contenu est obligatoire." }
        require(title.isNotBlank()) { "Le titre du contenu est obligatoire." }
        require(openCount >= 0) { "Le compteur d'ouvertures ne peut pas être négatif." }
    }

    fun toMap(): Map<String, String> = mapOf(
        "id" to id,
        "title" to title,
        "url" to url,
        "kind" to kind.name,
        "pinned" to pinned.toString(),
        "openCount" to openCount.toString(),
        "openCountingEnabled" to openCountingEnabled.toString(),
    )

    companion object {
        fun fromMap(map: Map<String, Any?>) = ContentItem(
            id = map.string("id"),
            title = map.string("title"),
            url = map.string("url"),
            kind = (map["kind"] as? String)?.let { raw ->
                runCatching { ContentKind.valueOf(raw) }.getOrNull()
            } ?: ContentUrlValidator.kindForLegacyUrl(map.string("url")),
            pinned = map.boolean("pinned") ?: false,
            openCount = map.int("openCount") ?: 0,
            openCountingEnabled = map.boolean("openCountingEnabled") ?: true,
        )
    }
}

object ContentUrlValidator {
    private val allowedHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")

    /** Accepts only public HTTPS links on the small, explicit YouTube host allowlist. */
    fun parse(rawUrl: String): URI {
        val uri = try {
            URI(rawUrl.trim())
        } catch (error: URISyntaxException) {
            throw IllegalArgumentException("URL de contenu invalide.", error)
        }
        require(uri.isAbsolute && !uri.isOpaque) { "L'URL doit être absolue." }
        require(uri.scheme.equals("https", ignoreCase = true)) { "Seules les URL HTTPS sont acceptées." }
        require(uri.rawUserInfo == null && uri.rawFragment == null) { "L'URL contient un composant interdit." }
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("L'URL doit contenir un nom d'hôte valide.")
        require(host in allowedHosts) { "Hôte de contenu non autorisé." }
        require(uri.port == -1 || uri.port == 443) { "Port de contenu non autorisé." }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath.startsWith("/")) { "Chemin de contenu invalide." }
        return uri
    }

    fun isValid(rawUrl: String): Boolean = try {
        parse(rawUrl)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Selected video links are intentionally narrower than the legacy YouTube host allowlist. */
    fun isSpecificVideoLink(rawUrl: String): Boolean = runCatching {
        val uri = parse(rawUrl)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val path = uri.path.orEmpty().trimEnd('/')
        if (host == "youtu.be") {
            path.trimStart('/').isNotBlank() && '/' !in path.trimStart('/')
        } else {
            when {
                path == "/watch" -> uri.rawQuery.orEmpty().split('&').any { it.startsWith("v=") && it.length > 2 }
                path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/") ->
                    path.substringAfterLast('/').isNotBlank()
                else -> false
            }
        }
    }.getOrDefault(false)

    fun isSearchUrl(rawUrl: String): Boolean = runCatching {
        val uri = parse(rawUrl)
        uri.path == "/results" && uri.rawQuery.orEmpty().split('&').any { it.startsWith("search_query=") }
    }.getOrDefault(false)

    internal fun kindForLegacyUrl(rawUrl: String): ContentKind =
        if (isSearchUrl(rawUrl)) ContentKind.SEARCH else ContentKind.VIDEO_LINK

    fun validateItem(item: ContentItem) {
        parse(item.url)
        when (item.kind) {
            ContentKind.VIDEO_LINK -> require(isSpecificVideoLink(item.url)) {
                "Ajoutez un lien vidéo YouTube précis, pas une page de recherche."
            }
            ContentKind.SEARCH -> require(isSearchUrl(item.url)) { "Une recherche doit pointer vers la page YouTube de résultats." }
        }
    }

    fun isAllowedSearchOrVideo(rawUrl: String): Boolean = isSpecificVideoLink(rawUrl) || isSearchUrl(rawUrl)
}

data class ContentOpenRequest(
    val action: String,
    val uri: URI,
    val chooserTitle: String,
) {
    init {
        require(action == ContentOpenRequestFactory.ACTION_VIEW) { "Seule l'action ACTION_VIEW est autorisée." }
        require(ContentUrlValidator.isValid(uri.toASCIIString())) { "L'URI de contenu n'est pas autorisée." }
        require(chooserTitle.isNotBlank()) { "Le titre du sélecteur est obligatoire." }
    }
}

object ContentOpenRequestFactory {
    const val ACTION_VIEW = "android.intent.action.VIEW"

    fun build(item: ContentItem): ContentOpenRequest {
        ContentUrlValidator.validateItem(item)
        return ContentOpenRequest(
            action = ACTION_VIEW,
            uri = ContentUrlValidator.parse(item.url),
            chooserTitle = if (item.kind == ContentKind.SEARCH) "Rechercher : ${item.title}" else "Ouvrir : ${item.title}",
        )
    }
}

enum class ContentOpenStatus { OPENED_CHOOSER, OPENED_DIRECT_FALLBACK, NO_COMPATIBLE_APP, INVALID_CONTENT }

data class ContentOpenResult(val status: ContentOpenStatus, val message: String)

interface ContentOpener {
    fun open(request: ContentOpenRequest): ContentOpenResult
}

/** Injectable bridge so chooser and direct-open fallback behavior is testable on the JVM. */
interface ContentLaunchGateway {
    /** Must be checked before showing a chooser because the chooser itself always resolves. */
    fun hasCompatibleHandler(request: ContentOpenRequest): Boolean = true
    fun openChooser(request: ContentOpenRequest): Boolean
    fun openDirect(request: ContentOpenRequest): Boolean
}

class FallbackContentOpener(private val gateway: ContentLaunchGateway) : ContentOpener {
    override fun open(request: ContentOpenRequest): ContentOpenResult {
        val hasHandler = safely { gateway.hasCompatibleHandler(request) }
        if (!hasHandler) {
            return ContentOpenResult(ContentOpenStatus.NO_COMPATIBLE_APP, "Aucune application ne peut ouvrir ce lien.")
        }
        val chooserOpened = safely { gateway.openChooser(request) }
        if (chooserOpened) return ContentOpenResult(ContentOpenStatus.OPENED_CHOOSER, "Choisissez une application pour ouvrir le lien.")
        val directOpened = safely { gateway.openDirect(request) }
        if (directOpened) return ContentOpenResult(ContentOpenStatus.OPENED_DIRECT_FALLBACK, "Lien ouvert dans l'application disponible.")
        return ContentOpenResult(ContentOpenStatus.NO_COMPATIBLE_APP, "Aucune application ne peut ouvrir ce lien.")
    }

    private inline fun safely(block: () -> Boolean): Boolean = try {
        block()
    } catch (_: RuntimeException) {
        false
    }
}

class ContentCatalog private constructor(items: List<ContentItem>) {
    private val byId = linkedMapOf<String, ContentItem>().apply { items.forEach { put(it.id, it) } }

    val items: List<ContentItem> @Synchronized get() = byId.values.sortedByDescending { it.pinned }

    @Synchronized
    fun find(id: String): ContentItem? = byId[id]

    /** Adds only a specific video link. Generic search URLs remain separate SEARCH entries. */
    @Synchronized
    fun addManualLink(title: String, url: String, id: String = UUID.randomUUID().toString()): ContentItem {
        val item = ContentItem(id, title.trim(), url.trim(), ContentKind.VIDEO_LINK)
        ContentUrlValidator.validateItem(item)
        require(id !in byId) { "Cet identifiant de contenu existe déjà." }
        require(byId.size < MAX_ITEMS) { "La bibliothèque ne peut pas dépasser $MAX_ITEMS éléments." }
        byId[id] = item
        return item
    }

    @Synchronized
    fun remove(id: String): Boolean = byId.remove(id) != null

    @Synchronized
    fun setPinned(id: String, pinned: Boolean): Boolean {
        val item = byId[id] ?: return false
        byId[id] = item.copy(pinned = pinned)
        return true
    }

    @Synchronized
    fun setOpenCountingEnabled(id: String, enabled: Boolean): Boolean {
        val item = byId[id] ?: return false
        byId[id] = item.copy(openCountingEnabled = enabled)
        return true
    }

    @Synchronized
    fun open(id: String, opener: ContentOpener): ContentOpenResult {
        val item = find(id) ?: return ContentOpenResult(ContentOpenStatus.INVALID_CONTENT, "Contenu introuvable.")
        val request = try {
            ContentOpenRequestFactory.build(item)
        } catch (_: IllegalArgumentException) {
            return ContentOpenResult(ContentOpenStatus.INVALID_CONTENT, "Cette URL de contenu n'est pas autorisée.")
        }
        val result = try {
            opener.open(request)
        } catch (_: RuntimeException) {
            ContentOpenResult(ContentOpenStatus.NO_COMPATIBLE_APP, "L'ouverture du lien a échoué.")
        }
        if (item.openCountingEnabled && result.status in setOf(ContentOpenStatus.OPENED_CHOOSER, ContentOpenStatus.OPENED_DIRECT_FALLBACK)) {
            byId[id] = item.copy(openCount = item.openCount + 1)
        }
        return result
    }

    @Synchronized
    fun toMaps(): List<Map<String, String>> = byId.values.map(ContentItem::toMap)

    companion object {
        fun create(items: List<ContentItem>): ContentCatalog {
            require(items.size <= MAX_ITEMS) { "La bibliothèque ne peut pas dépasser $MAX_ITEMS éléments." }
            require(items.map { it.id }.distinct().size == items.size) { "Les identifiants doivent être uniques." }
            items.forEach(ContentUrlValidator::validateItem)
            return ContentCatalog(items.toList())
        }

        fun demo(): ContentCatalog = create(
            listOf(
                ContentItem("focus-music", "Recherche démo : musique de concentration", "https://www.youtube.com/results?search_query=focus+music+demo", ContentKind.SEARCH),
                ContentItem("pomodoro", "Recherche démo : méthode Pomodoro", "https://www.youtube.com/results?search_query=pomodoro+technique+demo", ContentKind.SEARCH),
                ContentItem("science", "Recherche démo : sciences", "https://www.youtube.com/results?search_query=educational+science+demo", ContentKind.SEARCH),
            ),
        )

        fun fromMaps(items: List<Map<String, Any?>>): ContentCatalog = create(items.map(ContentItem::fromMap))

        const val MAX_ITEMS = 500
    }
}

private fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: throw IllegalArgumentException("$key must be a string")

private fun Map<String, Any?>.boolean(key: String): Boolean? = when (val value = this[key]) {
    is Boolean -> value
    is String -> value.toBooleanStrictOrNull()
    else -> null
}

private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}
