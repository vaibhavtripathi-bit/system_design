package com.systemdesign.socialmediafeed.approach_01_strategy_feed

import com.systemdesign.socialmediafeed.common.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Approach 1: Strategy Pattern for Feed Generation
 * 
 * Different feed generation algorithms can be swapped at runtime.
 * Supports push (fanout on write), pull (aggregate on read), and hybrid approaches.
 * 
 * Pattern: Strategy Pattern
 * 
 * Trade-offs:
 * + Different strategies for different user types (regular vs celebrity)
 * + Easy to A/B test feed algorithms
 * + Strategies testable in isolation
 * + Runtime strategy switching based on load
 * - Push strategy has high write amplification for celebrities
 * - Pull strategy has high read latency for users following many accounts
 * 
 * When to use:
 * - When feed generation varies by user type/size
 * - When experimenting with ranking algorithms
 * - When balancing read vs write performance
 * 
 * Extensibility:
 * - New feed strategy: Implement FeedGenerationStrategy interface
 * - ML-based ranking: Implement strategy that calls ML service
 * - Personalization: Create PersonalizedFeedStrategy with user preferences
 */

/**
 * Push-based feed strategy (Fanout on Write)
 * 
 * When a user posts, the post is immediately pushed to all followers' timelines.
 * Best for: Users with small follower counts
 * Drawback: Expensive for celebrities with millions of followers
 */
class PushFeedStrategy(
    private val config: FeedConfig = FeedConfig()
) : FeedGenerationStrategy {
    
    private val userTimelines = ConcurrentHashMap<String, MutableList<TimelineEntry>>()
    private val observers = mutableListOf<FeedObserver>()
    
    fun addObserver(observer: FeedObserver) {
        observers.add(observer)
    }
    
    fun fanoutPost(post: Post, author: User) {
        val entry = TimelineEntry(
            userId = "",
            postId = post.id,
            score = calculateScore(post, author)
        )
        
        author.followers.forEach { followerId ->
            val timeline = userTimelines.getOrPut(followerId) { mutableListOf() }
            val followerEntry = entry.copy(userId = followerId)
            timeline.add(0, followerEntry)
            
            if (timeline.size > config.maxItems) {
                timeline.removeAt(timeline.size - 1)
            }
        }
        
        observers.forEach { it.onNewPost(post) }
    }
    
    override fun generateFeed(
        userId: String,
        posts: List<Post>,
        users: Map<String, User>,
        limit: Int,
        cursor: String?
    ): FeedResult {
        val startTime = System.currentTimeMillis()
        
        val timeline = userTimelines[userId] ?: emptyList<TimelineEntry>()
        val startIndex = cursor?.toIntOrNull() ?: 0
        
        val postMap = posts.associateBy { it.id }
        
        val items = timeline
            .drop(startIndex)
            .take(limit)
            .mapNotNull { entry ->
                val post = postMap[entry.postId]
                val author = post?.let { users[it.authorId] }
                
                if (post != null && author != null) {
                    FeedItem(
                        post = post,
                        author = author,
                        score = entry.score,
                        reason = FeedItem.FeedReason.FOLLOWING
                    )
                } else null
            }
        
        val generationTime = System.currentTimeMillis() - startTime
        val hasMore = startIndex + limit < timeline.size
        val nextCursor = if (hasMore) (startIndex + limit).toString() else null
        
        return FeedResult(
            items = items,
            nextCursor = nextCursor,
            hasMore = hasMore,
            generationTimeMs = generationTime
        )
    }
    
    private fun calculateScore(post: Post, author: User): Double {
        val recencyScore = calculateRecencyScore(post.createdAt)
        val engagementScore = post.engagement.engagementRate * 100
        val authorScore = if (author.isVerified) 10.0 else 0.0
        
        return recencyScore * config.recencyWeight +
               engagementScore * config.engagementWeight +
               authorScore * config.relationshipWeight
    }
    
    private fun calculateRecencyScore(createdAt: LocalDateTime): Double {
        val hoursAgo = ChronoUnit.HOURS.between(createdAt, LocalDateTime.now())
        return maxOf(0.0, 100.0 - hoursAgo * 2)
    }
    
    fun clearTimeline(userId: String) {
        userTimelines.remove(userId)
    }
    
    fun getTimelineSize(userId: String): Int = userTimelines[userId]?.size ?: 0
}

/**
 * Pull-based feed strategy (Aggregate on Read)
 * 
 * When a user requests their feed, posts are aggregated from all followed users.
 * Best for: Celebrities with many followers
 * Drawback: Slow for users following many accounts
 */
class PullFeedStrategy(
    private val config: FeedConfig = FeedConfig()
) : FeedGenerationStrategy {
    
    override fun generateFeed(
        userId: String,
        posts: List<Post>,
        users: Map<String, User>,
        limit: Int,
        cursor: String?
    ): FeedResult {
        val startTime = System.currentTimeMillis()
        
        val user = users[userId] ?: return FeedResult(emptyList(), null, false)
        
        val cursorTime = cursor?.let { LocalDateTime.parse(it) }
        val staleThreshold = LocalDateTime.now().minusHours(config.staleThresholdHours.toLong())
        
        val relevantPosts = posts
            .filter { post ->
                val isFromFollowing = user.following.contains(post.authorId)
                val isOwnPost = post.authorId == userId
                val isNotStale = post.createdAt.isAfter(staleThreshold)
                val isAfterCursor = cursorTime == null || post.createdAt.isBefore(cursorTime)
                val isVisibleToUser = isPostVisibleToUser(post, user, users)
                
                (isFromFollowing || isOwnPost) && isNotStale && isAfterCursor && isVisibleToUser
            }
            .map { post ->
                val author = users[post.authorId]!!
                val score = calculateScore(post, author, user)
                FeedItem(
                    post = post,
                    author = author,
                    score = score,
                    reason = determineFeedReason(post, user)
                )
            }
            .sortedByDescending { it.score }
            .take(limit + 1)
        
        val hasMore = relevantPosts.size > limit
        val items = relevantPosts.take(limit)
        val nextCursor = if (hasMore && items.isNotEmpty()) {
            items.last().post.createdAt.toString()
        } else null
        
        val generationTime = System.currentTimeMillis() - startTime
        
        return FeedResult(
            items = items,
            nextCursor = nextCursor,
            hasMore = hasMore,
            generationTimeMs = generationTime
        )
    }
    
    private fun isPostVisibleToUser(post: Post, viewer: User, users: Map<String, User>): Boolean {
        val author = users[post.authorId] ?: return false
        
        return when (post.privacy) {
            PrivacyLevel.PUBLIC -> true
            PrivacyLevel.FOLLOWERS_ONLY -> author.followers.contains(viewer.id) || viewer.id == author.id
            PrivacyLevel.CLOSE_FRIENDS -> false
            PrivacyLevel.PRIVATE -> viewer.id == author.id
        }
    }
    
    private fun calculateScore(post: Post, author: User, viewer: User): Double {
        val recencyScore = calculateRecencyScore(post.createdAt)
        val engagementScore = calculateEngagementScore(post.engagement)
        val relationshipScore = calculateRelationshipScore(author, viewer)
        
        return recencyScore * config.recencyWeight +
               engagementScore * config.engagementWeight +
               relationshipScore * config.relationshipWeight
    }
    
    private fun calculateRecencyScore(createdAt: LocalDateTime): Double {
        val hoursAgo = ChronoUnit.HOURS.between(createdAt, LocalDateTime.now())
        return maxOf(0.0, 100.0 - hoursAgo * 2)
    }
    
    private fun calculateEngagementScore(engagement: Engagement): Double {
        val likeScore = minOf(engagement.likes.toDouble(), 1000.0) / 10
        val commentScore = minOf(engagement.comments.toDouble(), 500.0) / 5
        val shareScore = minOf(engagement.shares.toDouble(), 200.0) / 2
        
        return likeScore + commentScore + shareScore
    }
    
    private fun calculateRelationshipScore(author: User, viewer: User): Double {
        var score = 0.0
        
        if (author.isFollowedBy(viewer.id)) score += 30
        if (author.isFollowing(viewer.id)) score += 20
        if (author.isVerified) score += 10
        
        val mutualFollowers = author.followers.intersect(viewer.followers)
        score += minOf(mutualFollowers.size.toDouble(), 20.0)
        
        return score
    }
    
    private fun determineFeedReason(post: Post, viewer: User): FeedItem.FeedReason {
        return when {
            post.authorId == viewer.id -> FeedItem.FeedReason.FOLLOWING
            post.isRepost() -> FeedItem.FeedReason.REPOST_BY_FOLLOWING
            viewer.following.contains(post.authorId) -> FeedItem.FeedReason.FOLLOWING
            else -> FeedItem.FeedReason.SUGGESTED
        }
    }
}

/**
 * Hybrid feed strategy
 * 
 * Uses push for small accounts and pull for celebrities.
 * Balances write amplification and read latency.
 */
class HybridFeedStrategy(
    private val config: FeedConfig = FeedConfig(),
    private val pushStrategy: PushFeedStrategy = PushFeedStrategy(config),
    private val pullStrategy: PullFeedStrategy = PullFeedStrategy(config)
) : FeedGenerationStrategy {
    
    private val celebrityCache = ConcurrentHashMap<String, Boolean>()
    
    fun onNewPost(post: Post, author: User) {
        if (author.isCelebrity(config.pushThreshold)) {
            celebrityCache[author.id] = true
        } else {
            pushStrategy.fanoutPost(post, author)
        }
    }
    
    override fun generateFeed(
        userId: String,
        posts: List<Post>,
        users: Map<String, User>,
        limit: Int,
        cursor: String?
    ): FeedResult {
        val startTime = System.currentTimeMillis()
        
        val user = users[userId] ?: return FeedResult(emptyList(), null, false)
        
        val pushResult = pushStrategy.generateFeed(userId, posts, users, limit, cursor)
        
        val celebrityIds = user.following.filter { followingId ->
            celebrityCache[followingId] == true || 
            users[followingId]?.isCelebrity(config.pushThreshold) == true
        }
        
        val celebrityPosts = posts.filter { post ->
            celebrityIds.contains(post.authorId)
        }
        
        val pullResult = if (celebrityPosts.isNotEmpty()) {
            pullStrategy.generateFeed(userId, celebrityPosts, users, limit / 2, null)
        } else {
            FeedResult(emptyList(), null, false)
        }
        
        val mergedItems = mergeFeedResults(pushResult.items, pullResult.items, limit)
        
        val generationTime = System.currentTimeMillis() - startTime
        
        return FeedResult(
            items = mergedItems,
            nextCursor = pushResult.nextCursor,
            hasMore = pushResult.hasMore || pullResult.hasMore,
            generationTimeMs = generationTime
        )
    }
    
    private fun mergeFeedResults(
        pushItems: List<FeedItem>,
        pullItems: List<FeedItem>,
        limit: Int
    ): List<FeedItem> {
        val seenPostIds = mutableSetOf<String>()
        val merged = mutableListOf<FeedItem>()
        
        val allItems = (pushItems + pullItems).sortedByDescending { it.score }
        
        for (item in allItems) {
            if (!seenPostIds.contains(item.post.id)) {
                seenPostIds.add(item.post.id)
                merged.add(item)
                
                if (merged.size >= limit) break
            }
        }
        
        return merged
    }
    
    fun markAsCelebrity(userId: String) {
        celebrityCache[userId] = true
    }
    
    fun getCelebrityCount(): Int = celebrityCache.size
}

/**
 * Chronological feed strategy - simple reverse chronological order
 */
class ChronologicalFeedStrategy : FeedGenerationStrategy {
    
    override fun generateFeed(
        userId: String,
        posts: List<Post>,
        users: Map<String, User>,
        limit: Int,
        cursor: String?
    ): FeedResult {
        val startTime = System.currentTimeMillis()
        
        val user = users[userId] ?: return FeedResult(emptyList(), null, false)
        
        val cursorTime = cursor?.let { LocalDateTime.parse(it) }
        
        val items = posts
            .filter { post ->
                val isRelevant = user.following.contains(post.authorId) || post.authorId == userId
                val isAfterCursor = cursorTime == null || post.createdAt.isBefore(cursorTime)
                isRelevant && isAfterCursor
            }
            .sortedByDescending { it.createdAt }
            .take(limit + 1)
            .mapNotNull { post ->
                users[post.authorId]?.let { author ->
                    FeedItem(
                        post = post,
                        author = author,
                        score = 0.0,
                        reason = FeedItem.FeedReason.FOLLOWING
                    )
                }
            }
        
        val hasMore = items.size > limit
        val resultItems = items.take(limit)
        val nextCursor = if (hasMore && resultItems.isNotEmpty()) {
            resultItems.last().post.createdAt.toString()
        } else null
        
        val generationTime = System.currentTimeMillis() - startTime
        
        return FeedResult(
            items = resultItems,
            nextCursor = nextCursor,
            hasMore = hasMore,
            generationTimeMs = generationTime
        )
    }
}

/**
 * Trending/Explore feed strategy - shows popular content
 */
class TrendingFeedStrategy(
    private val trendingWindowHours: Int = 24,
    private val minEngagement: Int = 10
) : FeedGenerationStrategy {
    
    override fun generateFeed(
        userId: String,
        posts: List<Post>,
        users: Map<String, User>,
        limit: Int,
        cursor: String?
    ): FeedResult {
        val startTime = System.currentTimeMillis()
        
        val windowStart = LocalDateTime.now().minusHours(trendingWindowHours.toLong())
        val cursorScore = cursor?.toDoubleOrNull()
        
        val items = posts
            .filter { post ->
                val isRecent = post.createdAt.isAfter(windowStart)
                val isPublic = post.privacy == PrivacyLevel.PUBLIC
                val hasEngagement = post.engagement.totalEngagements >= minEngagement
                isRecent && isPublic && hasEngagement
            }
            .map { post ->
                val author = users[post.authorId]!!
                val score = calculateTrendingScore(post)
                FeedItem(
                    post = post,
                    author = author,
                    score = score,
                    reason = FeedItem.FeedReason.TRENDING
                )
            }
            .filter { cursorScore == null || it.score < cursorScore }
            .sortedByDescending { it.score }
            .take(limit + 1)
        
        val hasMore = items.size > limit
        val resultItems = items.take(limit)
        val nextCursor = if (hasMore && resultItems.isNotEmpty()) {
            resultItems.last().score.toString()
        } else null
        
        val generationTime = System.currentTimeMillis() - startTime
        
        return FeedResult(
            items = resultItems,
            nextCursor = nextCursor,
            hasMore = hasMore,
            generationTimeMs = generationTime
        )
    }
    
    private fun calculateTrendingScore(post: Post): Double {
        val hoursAgo = ChronoUnit.HOURS.between(post.createdAt, LocalDateTime.now()).coerceAtLeast(1)
        val engagement = post.engagement
        
        val engagementScore = engagement.likes * 1.0 +
                             engagement.comments * 3.0 +
                             engagement.shares * 5.0 +
                             engagement.reposts * 4.0
        
        return engagementScore / hoursAgo
    }
}

/**
 * Feed service that manages feed generation with interchangeable strategies
 */
class FeedService(
    private var strategy: FeedGenerationStrategy = PullFeedStrategy()
) {
    private val posts = mutableMapOf<String, Post>()
    private val users = mutableMapOf<String, User>()
    private val observers = mutableListOf<FeedObserver>()
    
    fun setStrategy(newStrategy: FeedGenerationStrategy) {
        strategy = newStrategy
    }
    
    fun addObserver(observer: FeedObserver) {
        observers.add(observer)
    }
    
    fun registerUser(user: User) {
        users[user.id] = user
    }
    
    fun createPost(post: Post) {
        posts[post.id] = post
        observers.forEach { it.onNewPost(post) }
        
        if (strategy is HybridFeedStrategy) {
            users[post.authorId]?.let { author ->
                (strategy as HybridFeedStrategy).onNewPost(post, author)
            }
        }
    }
    
    fun getFeed(userId: String, limit: Int = 20, cursor: String? = null): FeedResult {
        val result = strategy.generateFeed(userId, posts.values.toList(), users, limit, cursor)
        observers.forEach { it.onFeedRefresh(userId, result.items) }
        return result
    }
    
    fun likePost(postId: String, userId: String) {
        posts[postId]?.let { post ->
            val updated = post.withEngagement(post.engagement.like(userId))
            posts[postId] = updated
            observers.forEach { it.onPostEngagement(postId, Action.LIKE, userId) }
        }
    }
    
    fun sharePost(postId: String, userId: String) {
        posts[postId]?.let { post ->
            val updated = post.withEngagement(post.engagement.share(userId))
            posts[postId] = updated
            observers.forEach { it.onPostEngagement(postId, Action.SHARE, userId) }
        }
    }
    
    fun follow(followerId: String, followedId: String) {
        users[followerId]?.let { follower ->
            users[followerId] = follower.follow(followedId)
        }
        users[followedId]?.let { followed ->
            users[followedId] = followed.withFollower(followerId)
        }
    }
    
    fun unfollow(followerId: String, followedId: String) {
        users[followerId]?.let { follower ->
            users[followerId] = follower.unfollow(followedId)
        }
        users[followedId]?.let { followed ->
            users[followedId] = followed.withoutFollower(followerId)
        }
    }
    
    fun getPostCount(): Int = posts.size
    fun getUserCount(): Int = users.size
}
