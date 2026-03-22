package com.systemdesign.socialmediafeed.common

import java.time.LocalDateTime
import java.util.UUID

/**
 * Core domain models for Social Media Feed System.
 * 
 * Extensibility Points:
 * - New post types: Add to PostType enum and update content handling
 * - New feed strategies: Implement FeedGenerationStrategy interface
 * - New enrichment: Implement PostEnrichmentDecorator
 * - New engagement actions: Add to Action enum
 * 
 * Breaking Changes Required For:
 * - Changing user ID structure (requires migration)
 * - Modifying follower relationship model
 */

/** Post content types */
enum class PostType {
    TEXT,
    IMAGE,
    VIDEO,
    POLL,
    STORY
}

/** User actions on posts */
enum class Action {
    LIKE,
    COMMENT,
    SHARE,
    REPOST
}

/** Privacy level for posts */
enum class PrivacyLevel {
    PUBLIC,
    FOLLOWERS_ONLY,
    CLOSE_FRIENDS,
    PRIVATE
}

/** Represents a user in the system */
data class User(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val followers: Set<String> = emptySet(),
    val following: Set<String> = emptySet(),
    val isVerified: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    val followerCount: Int get() = followers.size
    val followingCount: Int get() = following.size
    
    fun isFollowing(userId: String): Boolean = following.contains(userId)
    fun isFollowedBy(userId: String): Boolean = followers.contains(userId)
    fun isCelebrity(threshold: Int = 10000): Boolean = followerCount >= threshold
    
    fun withFollower(followerId: String): User = copy(followers = followers + followerId)
    fun withoutFollower(followerId: String): User = copy(followers = followers - followerId)
    fun follow(userId: String): User = copy(following = following + userId)
    fun unfollow(userId: String): User = copy(following = following - userId)
}

/** Sealed class for different post content types */
sealed class PostContent {
    abstract val rawText: String?
    
    data class TextContent(
        val text: String,
        val mentions: List<String> = emptyList(),
        val hashtags: List<String> = emptyList()
    ) : PostContent() {
        override val rawText: String = text
    }
    
    data class ImageContent(
        val imageUrls: List<String>,
        val altText: String? = null,
        val caption: String? = null
    ) : PostContent() {
        override val rawText: String? = caption
    }
    
    data class VideoContent(
        val videoUrl: String,
        val thumbnailUrl: String? = null,
        val durationSeconds: Int,
        val caption: String? = null
    ) : PostContent() {
        override val rawText: String? = caption
    }
    
    data class PollContent(
        val question: String,
        val options: List<PollOption>,
        val expiresAt: LocalDateTime,
        val allowMultiple: Boolean = false
    ) : PostContent() {
        override val rawText: String = question
        
        fun isExpired(): Boolean = LocalDateTime.now().isAfter(expiresAt)
        fun totalVotes(): Int = options.sumOf { it.votes }
    }
    
    data class StoryContent(
        val mediaUrl: String,
        val mediaType: MediaType,
        val expiresAt: LocalDateTime,
        val viewerIds: Set<String> = emptySet()
    ) : PostContent() {
        override val rawText: String? = null
        
        fun isExpired(): Boolean = LocalDateTime.now().isAfter(expiresAt)
        fun hasBeenViewedBy(userId: String): Boolean = viewerIds.contains(userId)
        
        enum class MediaType { IMAGE, VIDEO }
    }
}

/** Poll option for PollContent */
data class PollOption(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val votes: Int = 0,
    val voterIds: Set<String> = emptySet()
) {
    fun addVote(userId: String): PollOption = 
        copy(votes = votes + 1, voterIds = voterIds + userId)
    
    fun hasVotedBy(userId: String): Boolean = voterIds.contains(userId)
}

/** Engagement metrics for a post */
data class Engagement(
    val likes: Int = 0,
    val comments: Int = 0,
    val shares: Int = 0,
    val reposts: Int = 0,
    val views: Int = 0,
    val saves: Int = 0,
    val likedBy: Set<String> = emptySet(),
    val sharedBy: Set<String> = emptySet()
) {
    val totalEngagements: Int get() = likes + comments + shares + reposts
    val engagementRate: Double get() = if (views > 0) totalEngagements.toDouble() / views else 0.0
    
    fun like(userId: String): Engagement = 
        if (likedBy.contains(userId)) this 
        else copy(likes = likes + 1, likedBy = likedBy + userId)
    
    fun unlike(userId: String): Engagement = 
        if (!likedBy.contains(userId)) this 
        else copy(likes = likes - 1, likedBy = likedBy - userId)
    
    fun share(userId: String): Engagement = 
        copy(shares = shares + 1, sharedBy = sharedBy + userId)
    
    fun incrementComments(): Engagement = copy(comments = comments + 1)
    fun incrementViews(): Engagement = copy(views = views + 1)
    fun incrementReposts(): Engagement = copy(reposts = reposts + 1)
    fun incrementSaves(): Engagement = copy(saves = saves + 1)
    
    fun isLikedBy(userId: String): Boolean = likedBy.contains(userId)
}

/** A post in the social media feed */
data class Post(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val content: PostContent,
    val type: PostType,
    val privacy: PrivacyLevel = PrivacyLevel.PUBLIC,
    val engagement: Engagement = Engagement(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val replyToId: String? = null,
    val repostOfId: String? = null,
    val location: String? = null,
    val tags: List<String> = emptyList()
) {
    fun isReply(): Boolean = replyToId != null
    fun isRepost(): Boolean = repostOfId != null
    fun isOriginal(): Boolean = !isReply() && !isRepost()
    
    fun withEngagement(newEngagement: Engagement): Post = 
        copy(engagement = newEngagement, updatedAt = LocalDateTime.now())
}

/** Feed item wrapping a post with additional context */
data class FeedItem(
    val id: String = UUID.randomUUID().toString(),
    val post: Post,
    val author: User,
    val timestamp: LocalDateTime = post.createdAt,
    val score: Double = 0.0,
    val reason: FeedReason = FeedReason.FOLLOWING,
    val enrichedData: EnrichedData? = null
) {
    enum class FeedReason {
        FOLLOWING,
        SUGGESTED,
        TRENDING,
        REPOST_BY_FOLLOWING,
        LIKED_BY_FOLLOWING,
        TOPIC_INTEREST,
        PROMOTED
    }
}

/** Enriched data added by decorators */
data class EnrichedData(
    val resolvedMediaUrls: List<String> = emptyList(),
    val engagementSummary: String? = null,
    val mutualFollowers: List<User> = emptyList(),
    val relatedPosts: List<Post> = emptyList(),
    val authorContext: String? = null,
    val trendingRank: Int? = null
)

/** Observer interface for feed events */
interface FeedObserver {
    fun onNewPost(post: Post)
    fun onPostEngagement(postId: String, action: Action, userId: String)
    fun onFeedRefresh(userId: String, items: List<FeedItem>)
    fun onPostDeleted(postId: String)
}

/** Strategy interface for feed generation */
interface FeedGenerationStrategy {
    fun generateFeed(
        userId: String,
        posts: List<Post>,
        users: Map<String, User>,
        limit: Int = 20,
        cursor: String? = null
    ): FeedResult
}

/** Result of feed generation */
data class FeedResult(
    val items: List<FeedItem>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val generationTimeMs: Long = 0
)

/** Timeline entry for push-based feeds */
data class TimelineEntry(
    val userId: String,
    val postId: String,
    val score: Double,
    val insertedAt: LocalDateTime = LocalDateTime.now()
)

/** Feed configuration */
data class FeedConfig(
    val maxItems: Int = 100,
    val recencyWeight: Double = 0.3,
    val engagementWeight: Double = 0.4,
    val relationshipWeight: Double = 0.3,
    val pushThreshold: Int = 10000,
    val staleThresholdHours: Int = 48
)

/** Comment on a post - base for composite pattern */
data class Comment(
    val id: String = UUID.randomUUID().toString(),
    val postId: String,
    val authorId: String,
    val content: String,
    val parentId: String? = null,
    val likes: Int = 0,
    val likedBy: Set<String> = emptySet(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val isDeleted: Boolean = false,
    val mentions: List<String> = emptyList()
) {
    fun isTopLevel(): Boolean = parentId == null
    fun isReply(): Boolean = parentId != null
    
    fun like(userId: String): Comment = 
        if (likedBy.contains(userId)) this 
        else copy(likes = likes + 1, likedBy = likedBy + userId)
    
    fun unlike(userId: String): Comment = 
        if (!likedBy.contains(userId)) this 
        else copy(likes = likes - 1, likedBy = likedBy - userId)
}

/** Feed events for observer pattern */
sealed class FeedEvent {
    abstract val timestamp: LocalDateTime
    
    data class PostCreated(
        val post: Post,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : FeedEvent()
    
    data class PostLiked(
        val postId: String,
        val userId: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : FeedEvent()
    
    data class PostCommented(
        val postId: String,
        val comment: Comment,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : FeedEvent()
    
    data class PostShared(
        val postId: String,
        val userId: String,
        val shareType: Action,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : FeedEvent()
    
    data class UserFollowed(
        val followerId: String,
        val followedId: String,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : FeedEvent()
    
    data class FeedGenerated(
        val userId: String,
        val itemCount: Int,
        val generationTimeMs: Long,
        override val timestamp: LocalDateTime = LocalDateTime.now()
    ) : FeedEvent()
}

/** Base interface for post enrichment */
interface PostEnricher {
    fun enrich(feedItem: FeedItem): FeedItem
}

/** Comment tree node for composite pattern */
interface CommentComponent {
    val id: String
    val content: String
    val authorId: String
    val likes: Int
    val createdAt: LocalDateTime
    
    fun getChildCount(): Int
    fun getTotalCount(): Int
}
