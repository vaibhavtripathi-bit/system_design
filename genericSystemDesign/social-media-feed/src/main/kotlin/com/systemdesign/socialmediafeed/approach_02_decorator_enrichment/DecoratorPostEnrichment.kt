package com.systemdesign.socialmediafeed.approach_02_decorator_enrichment

import com.systemdesign.socialmediafeed.common.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Approach 2: Decorator Pattern for Post Enrichment
 * 
 * Post enrichment decorators can be stacked to add different types of context.
 * Each decorator adds specific data without modifying the core feed item.
 * 
 * Pattern: Decorator Pattern
 * 
 * Trade-offs:
 * + Enrichments are composable and stackable
 * + Easy to add new enrichment types
 * + Order of decorators can affect performance
 * + Each enrichment is testable in isolation
 * - Can add latency if enrichments require external calls
 * - Need to balance enrichment depth vs response time
 * 
 * When to use:
 * - When posts need different context in different views
 * - When enrichments can be independently enabled/disabled
 * - When building personalized feed experiences
 * 
 * Extensibility:
 * - New enrichment: Implement PostEnrichmentDecorator
 * - Conditional enrichment: Check conditions before applying
 * - Async enrichment: Create AsyncEnrichmentDecorator variant
 */

/** Base enricher that returns items unchanged */
class BaseEnricher : PostEnricher {
    override fun enrich(feedItem: FeedItem): FeedItem = feedItem
}

/** Abstract decorator for post enrichment */
abstract class PostEnrichmentDecorator(
    protected val wrapped: PostEnricher
) : PostEnricher

/**
 * Media URL resolution decorator
 * 
 * Resolves CDN URLs for images and videos, potentially adding
 * different resolutions or formats based on client capabilities.
 */
class MediaEnrichmentDecorator(
    wrapped: PostEnricher,
    private val mediaResolver: MediaResolver
) : PostEnrichmentDecorator(wrapped) {
    
    override fun enrich(feedItem: FeedItem): FeedItem {
        val enriched = wrapped.enrich(feedItem)
        val resolvedUrls = resolveMediaUrls(enriched.post.content)
        
        val currentData = enriched.enrichedData ?: EnrichedData()
        val updatedData = currentData.copy(resolvedMediaUrls = resolvedUrls)
        
        return enriched.copy(enrichedData = updatedData)
    }
    
    private fun resolveMediaUrls(content: PostContent): List<String> {
        return when (content) {
            is PostContent.ImageContent -> 
                content.imageUrls.map { mediaResolver.resolveImageUrl(it) }
            is PostContent.VideoContent -> 
                listOf(mediaResolver.resolveVideoUrl(content.videoUrl))
            is PostContent.StoryContent -> 
                listOf(mediaResolver.resolveMediaUrl(content.mediaUrl))
            else -> emptyList()
        }
    }
}

/** Interface for media URL resolution */
interface MediaResolver {
    fun resolveImageUrl(url: String, size: ImageSize = ImageSize.MEDIUM): String
    fun resolveVideoUrl(url: String, quality: VideoQuality = VideoQuality.AUTO): String
    fun resolveMediaUrl(url: String): String
    
    enum class ImageSize { THUMBNAIL, SMALL, MEDIUM, LARGE, ORIGINAL }
    enum class VideoQuality { LOW, MEDIUM, HIGH, AUTO }
}

/** Simple CDN-based media resolver */
class CdnMediaResolver(
    private val cdnBaseUrl: String = "https://cdn.example.com"
) : MediaResolver {
    
    override fun resolveImageUrl(url: String, size: MediaResolver.ImageSize): String {
        val sizeParam = when (size) {
            MediaResolver.ImageSize.THUMBNAIL -> "w=100"
            MediaResolver.ImageSize.SMALL -> "w=300"
            MediaResolver.ImageSize.MEDIUM -> "w=600"
            MediaResolver.ImageSize.LARGE -> "w=1200"
            MediaResolver.ImageSize.ORIGINAL -> ""
        }
        return "$cdnBaseUrl/images/${extractFileName(url)}?$sizeParam"
    }
    
    override fun resolveVideoUrl(url: String, quality: MediaResolver.VideoQuality): String {
        val qualityParam = when (quality) {
            MediaResolver.VideoQuality.LOW -> "q=480"
            MediaResolver.VideoQuality.MEDIUM -> "q=720"
            MediaResolver.VideoQuality.HIGH -> "q=1080"
            MediaResolver.VideoQuality.AUTO -> "q=auto"
        }
        return "$cdnBaseUrl/videos/${extractFileName(url)}?$qualityParam"
    }
    
    override fun resolveMediaUrl(url: String): String {
        return "$cdnBaseUrl/media/${extractFileName(url)}"
    }
    
    private fun extractFileName(url: String): String {
        return url.substringAfterLast("/")
    }
}

/**
 * Engagement summary decorator
 * 
 * Adds human-readable engagement summaries like "Liked by user1 and 42 others"
 */
class EngagementDecorator(
    wrapped: PostEnricher,
    private val userRepository: UserRepository
) : PostEnrichmentDecorator(wrapped) {
    
    override fun enrich(feedItem: FeedItem): FeedItem {
        val enriched = wrapped.enrich(feedItem)
        val summary = generateEngagementSummary(enriched.post.engagement, feedItem.author)
        
        val currentData = enriched.enrichedData ?: EnrichedData()
        val updatedData = currentData.copy(engagementSummary = summary)
        
        return enriched.copy(enrichedData = updatedData)
    }
    
    private fun generateEngagementSummary(engagement: Engagement, author: User): String {
        val likes = engagement.likes
        val comments = engagement.comments
        
        if (likes == 0 && comments == 0) {
            return "Be the first to like this"
        }
        
        val likeSummary = when {
            likes == 0 -> null
            likes == 1 -> {
                val likerId = engagement.likedBy.firstOrNull()
                val liker = likerId?.let { userRepository.getUser(it) }
                liker?.let { "Liked by ${it.displayName}" } ?: "1 like"
            }
            likes <= 3 -> {
                val likerNames = engagement.likedBy
                    .take(3)
                    .mapNotNull { userRepository.getUser(it)?.displayName }
                "Liked by ${likerNames.joinToString(", ")}"
            }
            else -> {
                val firstLiker = engagement.likedBy.firstOrNull()
                    ?.let { userRepository.getUser(it) }
                firstLiker?.let {
                    "Liked by ${it.displayName} and ${likes - 1} others"
                } ?: "$likes likes"
            }
        }
        
        val commentSummary = when {
            comments == 0 -> null
            comments == 1 -> "1 comment"
            else -> "$comments comments"
        }
        
        return listOfNotNull(likeSummary, commentSummary).joinToString(" · ")
    }
}

/** Repository interface for user data */
interface UserRepository {
    fun getUser(userId: String): User?
    fun getUsers(userIds: Collection<String>): Map<String, User>
}

/** In-memory user repository implementation */
class InMemoryUserRepository(
    private val users: MutableMap<String, User> = mutableMapOf()
) : UserRepository {
    
    override fun getUser(userId: String): User? = users[userId]
    
    override fun getUsers(userIds: Collection<String>): Map<String, User> {
        return userIds.mapNotNull { id -> users[id]?.let { id to it } }.toMap()
    }
    
    fun addUser(user: User) {
        users[user.id] = user
    }
}

/**
 * Relationship context decorator
 * 
 * Adds "followed by X" context showing mutual connections
 */
class RelationshipDecorator(
    wrapped: PostEnricher,
    private val userRepository: UserRepository,
    private val viewerIdProvider: () -> String
) : PostEnrichmentDecorator(wrapped) {
    
    override fun enrich(feedItem: FeedItem): FeedItem {
        val enriched = wrapped.enrich(feedItem)
        val viewerId = viewerIdProvider()
        val viewer = userRepository.getUser(viewerId) ?: return enriched
        
        val mutualFollowers = findMutualFollowers(feedItem.author, viewer)
        val authorContext = generateAuthorContext(feedItem.author, viewer, mutualFollowers)
        
        val currentData = enriched.enrichedData ?: EnrichedData()
        val updatedData = currentData.copy(
            mutualFollowers = mutualFollowers,
            authorContext = authorContext
        )
        
        return enriched.copy(enrichedData = updatedData)
    }
    
    private fun findMutualFollowers(author: User, viewer: User): List<User> {
        val mutualIds = author.followers.intersect(viewer.following)
        return mutualIds.take(3).mapNotNull { userRepository.getUser(it) }
    }
    
    private fun generateAuthorContext(
        author: User,
        viewer: User,
        mutualFollowers: List<User>
    ): String? {
        return when {
            author.isFollowedBy(viewer.id) && author.isFollowing(viewer.id) ->
                "You follow each other"
            mutualFollowers.isNotEmpty() -> {
                val names = mutualFollowers.take(2).map { it.displayName }
                val suffix = if (mutualFollowers.size > 2) {
                    " and ${mutualFollowers.size - 2} others you follow"
                } else {
                    " follows"
                }
                "${names.joinToString(", ")}$suffix"
            }
            author.isVerified -> "Verified account"
            else -> null
        }
    }
}

/**
 * Trending rank decorator
 * 
 * Adds trending rank information for posts that are trending
 */
class TrendingDecorator(
    wrapped: PostEnricher,
    private val trendingService: TrendingService
) : PostEnrichmentDecorator(wrapped) {
    
    override fun enrich(feedItem: FeedItem): FeedItem {
        val enriched = wrapped.enrich(feedItem)
        val rank = trendingService.getTrendingRank(enriched.post.id)
        
        if (rank == null) return enriched
        
        val currentData = enriched.enrichedData ?: EnrichedData()
        val updatedData = currentData.copy(trendingRank = rank)
        
        return enriched.copy(
            enrichedData = updatedData,
            reason = FeedItem.FeedReason.TRENDING
        )
    }
}

/** Service for tracking trending posts */
interface TrendingService {
    fun getTrendingRank(postId: String): Int?
    fun updateTrendingPosts(posts: List<Post>)
}

/** Simple trending service implementation */
class SimpleTrendingService(
    private val windowHours: Int = 24,
    private val maxTrending: Int = 100
) : TrendingService {
    
    private val trendingRanks = mutableMapOf<String, Int>()
    
    override fun getTrendingRank(postId: String): Int? = trendingRanks[postId]
    
    override fun updateTrendingPosts(posts: List<Post>) {
        trendingRanks.clear()
        
        val windowStart = LocalDateTime.now().minusHours(windowHours.toLong())
        
        posts
            .filter { it.createdAt.isAfter(windowStart) }
            .sortedByDescending { calculateTrendingScore(it) }
            .take(maxTrending)
            .forEachIndexed { index, post ->
                trendingRanks[post.id] = index + 1
            }
    }
    
    private fun calculateTrendingScore(post: Post): Double {
        val hoursAgo = ChronoUnit.HOURS.between(post.createdAt, LocalDateTime.now()).coerceAtLeast(1)
        val engagement = post.engagement
        
        return (engagement.likes + engagement.comments * 2 + engagement.shares * 3).toDouble() / hoursAgo
    }
}

/**
 * Related posts decorator
 * 
 * Adds related posts suggestions based on hashtags or content similarity
 */
class RelatedPostsDecorator(
    wrapped: PostEnricher,
    private val postRepository: PostRepository,
    private val maxRelated: Int = 3
) : PostEnrichmentDecorator(wrapped) {
    
    override fun enrich(feedItem: FeedItem): FeedItem {
        val enriched = wrapped.enrich(feedItem)
        val relatedPosts = findRelatedPosts(enriched.post)
        
        if (relatedPosts.isEmpty()) return enriched
        
        val currentData = enriched.enrichedData ?: EnrichedData()
        val updatedData = currentData.copy(relatedPosts = relatedPosts)
        
        return enriched.copy(enrichedData = updatedData)
    }
    
    private fun findRelatedPosts(post: Post): List<Post> {
        val tags = post.tags
        if (tags.isEmpty()) return emptyList()
        
        return postRepository.findByTags(tags)
            .filter { it.id != post.id }
            .sortedByDescending { it.engagement.totalEngagements }
            .take(maxRelated)
    }
}

/** Repository interface for post data */
interface PostRepository {
    fun getPost(postId: String): Post?
    fun findByTags(tags: List<String>): List<Post>
    fun findByAuthor(authorId: String, limit: Int = 10): List<Post>
}

/** In-memory post repository implementation */
class InMemoryPostRepository(
    private val posts: MutableMap<String, Post> = mutableMapOf()
) : PostRepository {
    
    override fun getPost(postId: String): Post? = posts[postId]
    
    override fun findByTags(tags: List<String>): List<Post> {
        return posts.values.filter { post ->
            post.tags.any { it in tags }
        }
    }
    
    override fun findByAuthor(authorId: String, limit: Int): List<Post> {
        return posts.values
            .filter { it.authorId == authorId }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }
    
    fun addPost(post: Post) {
        posts[post.id] = post
    }
}

/**
 * Time context decorator
 * 
 * Adds human-readable time context like "2 hours ago" or "Yesterday at 3:00 PM"
 */
class TimeContextDecorator(
    wrapped: PostEnricher
) : PostEnrichmentDecorator(wrapped) {
    
    override fun enrich(feedItem: FeedItem): FeedItem {
        val enriched = wrapped.enrich(feedItem)
        
        return enriched
    }
    
    fun formatTimeAgo(dateTime: LocalDateTime): String {
        val now = LocalDateTime.now()
        val minutes = ChronoUnit.MINUTES.between(dateTime, now)
        val hours = ChronoUnit.HOURS.between(dateTime, now)
        val days = ChronoUnit.DAYS.between(dateTime, now)
        
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            else -> dateTime.toLocalDate().toString()
        }
    }
}

/**
 * Conditional enrichment decorator
 * 
 * Only applies enrichment if condition is met
 */
class ConditionalEnrichmentDecorator(
    wrapped: PostEnricher,
    private val condition: (FeedItem) -> Boolean,
    private val enricher: PostEnricher
) : PostEnrichmentDecorator(wrapped) {
    
    override fun enrich(feedItem: FeedItem): FeedItem {
        val enriched = wrapped.enrich(feedItem)
        
        return if (condition(enriched)) {
            enricher.enrich(enriched)
        } else {
            enriched
        }
    }
}

/**
 * Builder for constructing enrichment pipelines
 */
class EnrichmentPipelineBuilder {
    private var enricher: PostEnricher = BaseEnricher()
    
    fun withMediaResolution(mediaResolver: MediaResolver): EnrichmentPipelineBuilder {
        enricher = MediaEnrichmentDecorator(enricher, mediaResolver)
        return this
    }
    
    fun withEngagementSummary(userRepository: UserRepository): EnrichmentPipelineBuilder {
        enricher = EngagementDecorator(enricher, userRepository)
        return this
    }
    
    fun withRelationshipContext(
        userRepository: UserRepository,
        viewerIdProvider: () -> String
    ): EnrichmentPipelineBuilder {
        enricher = RelationshipDecorator(enricher, userRepository, viewerIdProvider)
        return this
    }
    
    fun withTrendingRank(trendingService: TrendingService): EnrichmentPipelineBuilder {
        enricher = TrendingDecorator(enricher, trendingService)
        return this
    }
    
    fun withRelatedPosts(
        postRepository: PostRepository,
        maxRelated: Int = 3
    ): EnrichmentPipelineBuilder {
        enricher = RelatedPostsDecorator(enricher, postRepository, maxRelated)
        return this
    }
    
    fun withTimeContext(): EnrichmentPipelineBuilder {
        enricher = TimeContextDecorator(enricher)
        return this
    }
    
    fun withConditional(
        condition: (FeedItem) -> Boolean,
        enricher: PostEnricher
    ): EnrichmentPipelineBuilder {
        this.enricher = ConditionalEnrichmentDecorator(this.enricher, condition, enricher)
        return this
    }
    
    fun build(): PostEnricher = enricher
}

/**
 * Enrichment service for batch processing
 */
class EnrichmentService(
    private val enricher: PostEnricher
) {
    fun enrichFeedItems(items: List<FeedItem>): List<FeedItem> {
        return items.map { enricher.enrich(it) }
    }
    
    fun enrichFeedItem(item: FeedItem): FeedItem {
        return enricher.enrich(item)
    }
}
