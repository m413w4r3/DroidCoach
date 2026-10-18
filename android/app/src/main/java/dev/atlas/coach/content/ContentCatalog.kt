package dev.atlas.coach.content

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

data class ContentItem(val id: String, val title: String, val url: String) {
    init {
        require(id.isNotBlank()) { "L'identifiant du contenu est obligatoire." }
        require(title.isNotBlank()) { "Le titre du contenu est obligatoire." }
    }

    fun toMap(): Map<String, String> = mapOf("id" to id, "title" to title, "url" to url)

    companion object {
        fun fromMap(map: Map<String, Any?>) = ContentItem(
            id = map.string("id"),
            title = map.string("title"),
            url = map.string("url"),
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

    fun build(item: ContentItem): ContentOpenRequest = ContentOpenRequest(
        action = ACTION_VIEW,
        uri = ContentUrlValidator.parse(item.url),
        chooserTitle = "Ouvrir : ${item.title}",
    )
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

class ContentCatalog private constructor(val items: List<ContentItem>) {
    fun find(id: String): ContentItem? = items.firstOrNull { it.id == id }

    fun open(id: String, opener: ContentOpener): ContentOpenResult {
        val item = find(id) ?: return ContentOpenResult(ContentOpenStatus.INVALID_CONTENT, "Contenu introuvable.")
        val request = try {
            ContentOpenRequestFactory.build(item)
        } catch (_: IllegalArgumentException) {
            return ContentOpenResult(ContentOpenStatus.INVALID_CONTENT, "Cette URL de contenu n'est pas autorisée.")
        }
        return opener.open(request)
    }

    fun toMaps(): List<Map<String, String>> = items.map(ContentItem::toMap)

    companion object {
        fun create(items: List<ContentItem>): ContentCatalog {
            require(items.size <= MAX_ITEMS) { "Le catalogue ne peut pas dépasser $MAX_ITEMS contenus." }
            require(items.map { it.id }.distinct().size == items.size) { "Les identifiants doivent être uniques." }
            items.forEach { ContentUrlValidator.parse(it.url) }
            return ContentCatalog(items.toList())
        }

        fun demo(): ContentCatalog = create(
            listOf(
                ContentItem("focus-music", "Recherche démo : musique de concentration", "https://www.youtube.com/results?search_query=focus+music+demo"),
                ContentItem("pomodoro", "Recherche démo : méthode Pomodoro", "https://www.youtube.com/results?search_query=pomodoro+technique+demo"),
                ContentItem("science", "Recherche démo : sciences", "https://www.youtube.com/results?search_query=educational+science+demo"),
            ),
        )

        fun fromMaps(items: List<Map<String, Any?>>): ContentCatalog = create(items.map(ContentItem::fromMap))

        const val MAX_ITEMS = 3
    }
}

private fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: throw IllegalArgumentException("$key must be a string")
