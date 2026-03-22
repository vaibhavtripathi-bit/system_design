package com.systemdesign.socialmediafeed

import com.systemdesign.socialmediafeed.common.*
import com.systemdesign.socialmediafeed.approach_01_strategy_feed.*
import com.systemdesign.socialmediafeed.approach_02_decorator_enrichment.*
import com.systemdesign.socialmediafeed.approach_03_composite_comments.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import java.time.LocalDateTime

class SocialMediaFeedTest {
    
    private fun createTestUser(
        id: String = "user-1",
        username: String = "testuser",
        followers: Set<String> = emptySet(),
        following: Set<String> = emptySet()
    ): User {
        return User(
            id = id,
            username = username,
            displayName = "Test User",
            followers = followers,
            following = following
        )
    }
    
    private fun createTestPost(
        id: String = "post-1",
        authorId: String = "user-1",
        content: PostContent = PostContent.TextContent("Test post"),
        engagement: Engagement = Engagement(),
        createdAt: LocalDateTime = LocalDateTime.now()
    ): Post {
        return Post(
            id = id,
            authorId = authorId,
            content = content,
            type = PostType.TEXT,
            engagement = engagement,
            createdAt = createdAt
        )
    }
    
    @Nested
    inner class FeedStrategyTest {
        
        @Test
        fun `pull strategy generates feed from followed users`() {
            val strategy = PullFeedStrategy()
            
            val viewer = createTestUser(id = "viewer", following = setOf("author-1", "author-2"))
            val author1 = createTestUser(id = "author-1")
            val author2 = createTestUser(id = "author-2")
            val stranger = createTestUser(id = "stranger")
            
            val users = mapOf(
                "viewer" to viewer,
                "author-1" to author1,
                "author-2" to author2,
                "stranger" to stranger
            )
            
            val posts = listOf(
                createTestPost(id = "p1", authorId = "author-1"),
                createTestPost(id = "p2", authorId = "author-2"),
                createTestPost(id = "p3", authorId = "stranger")
            )
            
            val result = strategy.generateFeed("viewer", posts, users, 10)
            
            assertEquals(2, result.items.size)
            assertTrue(result.items.all { it.post.authorId in listOf("author-1", "author-2") })
            assertFalse(result.items.any { it.post.authorId == "stranger" })
        }
        
        @Test
        fun `pull strategy respects privacy settings`() {
            val strategy = PullFeedStrategy()
            
            val viewer = createTestUser(id = "viewer", following = setOf("author"))
            val author = createTestUser(id = "author", followers = emptySet())
            
            val users = mapOf("viewer" to viewer, "author" to author)
            
            val publicPost = createTestPost(id = "public", authorId = "author")
            val privatePost = createTestPost(id = "private", authorId = "author")
                .copy(privacy = PrivacyLevel.PRIVATE)
            
            val posts = listOf(publicPost, privatePost)
            
            val result = strategy.generateFeed("viewer", posts, users, 10)
            
            assertEquals(1, result.items.size)
            assertEquals("public", result.items.first().post.id)
        }
        
        @Test
        fun `pull strategy includes user's own posts`() {
            val strategy = PullFeedStrategy()
            
            val user = createTestUser(id = "user-1", following = emptySet())
            val users = mapOf("user-1" to user)
            
            val ownPost = createTestPost(id = "own", authorId = "user-1")
            val posts = listOf(ownPost)
            
            val result = strategy.generateFeed("user-1", posts, users, 10)
            
            assertEquals(1, result.items.size)
            assertEquals("own", result.items.first().post.id)
        }
        
        @Test
        fun `push strategy fanouts posts to followers`() {
            val strategy = PushFeedStrategy()
            
            val author = createTestUser(
                id = "author",
                followers = setOf("follower-1", "follower-2")
            )
            
            val post = createTestPost(id = "new-post", authorId = "author")
            strategy.fanoutPost(post, author)
            
            assertEquals(1, strategy.getTimelineSize("follower-1"))
            assertEquals(1, strategy.getTimelineSize("follower-2"))
            assertEquals(0, strategy.getTimelineSize("non-follower"))
        }
        
        @Test
        fun `hybrid strategy uses push for small accounts`() {
            val config = FeedConfig(pushThreshold = 10000)
            val strategy = HybridFeedStrategy(config)
            
            val smallAuthor = createTestUser(
                id = "small-author",
                followers = (1..100).map { "f$it" }.toSet()
            )
            
            val post = createTestPost(id = "post", authorId = "small-author")
            strategy.onNewPost(post, smallAuthor)
            
            assertEquals(0, strategy.getCelebrityCount())
        }
        
        @Test
        fun `hybrid strategy marks celebrities for pull`() {
            val config = FeedConfig(pushThreshold = 100)
            val strategy = HybridFeedStrategy(config)
            
            val celebrity = createTestUser(
                id = "celebrity",
                followers = (1..1000).map { "f$it" }.toSet()
            )
            
            val post = createTestPost(id = "post", authorId = "celebrity")
            strategy.onNewPost(post, celebrity)
            
            assertEquals(1, strategy.getCelebrityCount())
        }
        
        @Test
        fun `chronological strategy returns posts in time order`() {
            val strategy = ChronologicalFeedStrategy()
            
            val user = createTestUser(id = "user", following = setOf("author"))
            val author = createTestUser(id = "author")
            val users = mapOf("user" to user, "author" to author)
            
            val now = LocalDateTime.now()
            val posts = listOf(
                createTestPost(id = "old", authorId = "author", createdAt = now.minusHours(2)),
                createTestPost(id = "new", authorId = "author", createdAt = now),
                createTestPost(id = "mid", authorId = "author", createdAt = now.minusHours(1))
            )
            
            val result = strategy.generateFeed("user", posts, users, 10)
            
            assertEquals(3, result.items.size)
            assertEquals("new", result.items[0].post.id)
            assertEquals("mid", result.items[1].post.id)
            assertEquals("old", result.items[2].post.id)
        }
        
        @Test
        fun `trending strategy returns popular public posts`() {
            val strategy = TrendingFeedStrategy(trendingWindowHours = 24, minEngagement = 5)
            
            val author = createTestUser(id = "author")
            val users = mapOf("author" to author)
            
            val popularPost = createTestPost(
                id = "popular",
                authorId = "author",
                engagement = Engagement(likes = 100, comments = 50)
            )
            val unpopularPost = createTestPost(
                id = "unpopular",
                authorId = "author",
                engagement = Engagement(likes = 2)
            )
            val posts = listOf(popularPost, unpopularPost)
            
            val result = strategy.generateFeed("viewer", posts, users, 10)
            
            assertEquals(1, result.items.size)
            assertEquals("popular", result.items.first().post.id)
            assertEquals(FeedItem.FeedReason.TRENDING, result.items.first().reason)
        }
        
        @Test
        fun `feed service allows strategy switching`() {
            val service = FeedService(ChronologicalFeedStrategy())
            
            val user = createTestUser(id = "user", following = setOf("author"))
            val author = createTestUser(id = "author")
            service.registerUser(user)
            service.registerUser(author)
            
            val post = createTestPost(id = "post", authorId = "author")
            service.createPost(post)
            
            var result = service.getFeed("user")
            assertEquals(1, result.items.size)
            
            service.setStrategy(TrendingFeedStrategy(minEngagement = 100))
            result = service.getFeed("user")
            assertEquals(0, result.items.size)
        }
        
        @Test
        fun `feed pagination works correctly`() {
            val strategy = PullFeedStrategy()
            
            val user = createTestUser(id = "user", following = setOf("author"))
            val author = createTestUser(id = "author")
            val users = mapOf("user" to user, "author" to author)
            
            val now = LocalDateTime.now()
            val posts = (1..10).map { i ->
                createTestPost(
                    id = "post-$i",
                    authorId = "author",
                    createdAt = now.minusMinutes(i.toLong())
                )
            }
            
            val page1 = strategy.generateFeed("user", posts, users, 3)
            assertEquals(3, page1.items.size)
            assertTrue(page1.hasMore)
            assertNotNull(page1.nextCursor)
            
            val page2 = strategy.generateFeed("user", posts, users, 3, page1.nextCursor)
            assertEquals(3, page2.items.size)
        }
    }
    
    @Nested
    inner class EnrichmentDecoratorTest {
        
        private lateinit var userRepository: InMemoryUserRepository
        
        @BeforeEach
        fun setup() {
            userRepository = InMemoryUserRepository()
        }
        
        @Test
        fun `base enricher returns item unchanged`() {
            val enricher = BaseEnricher()
            val author = createTestUser()
            val post = createTestPost()
            val feedItem = FeedItem(post = post, author = author)
            
            val result = enricher.enrich(feedItem)
            
            assertEquals(feedItem, result)
        }
        
        @Test
        fun `media enrichment resolves URLs`() {
            val mediaResolver = CdnMediaResolver("https://cdn.test.com")
            val enricher = MediaEnrichmentDecorator(BaseEnricher(), mediaResolver)
            
            val author = createTestUser()
            val post = createTestPost(
                content = PostContent.ImageContent(
                    imageUrls = listOf("img1.jpg", "img2.jpg"),
                    caption = "Test images"
                )
            ).copy(type = PostType.IMAGE)
            
            val feedItem = FeedItem(post = post, author = author)
            val result = enricher.enrich(feedItem)
            
            assertNotNull(result.enrichedData)
            assertEquals(2, result.enrichedData!!.resolvedMediaUrls.size)
            assertTrue(result.enrichedData!!.resolvedMediaUrls.all { 
                it.startsWith("https://cdn.test.com") 
            })
        }
        
        @Test
        fun `engagement decorator generates summary`() {
            val liker = createTestUser(id = "liker", username = "liker")
            userRepository.addUser(liker)
            
            val enricher = EngagementDecorator(BaseEnricher(), userRepository)
            
            val author = createTestUser()
            val post = createTestPost(
                engagement = Engagement(
                    likes = 5,
                    comments = 3,
                    likedBy = setOf("liker")
                )
            )
            
            val feedItem = FeedItem(post = post, author = author)
            val result = enricher.enrich(feedItem)
            
            assertNotNull(result.enrichedData)
            assertNotNull(result.enrichedData!!.engagementSummary)
            assertTrue(result.enrichedData!!.engagementSummary!!.contains("Test User"))
        }
        
        @Test
        fun `relationship decorator shows mutual connections`() {
            val viewer = createTestUser(
                id = "viewer",
                following = setOf("mutual-1", "mutual-2")
            )
            val mutual1 = createTestUser(id = "mutual-1", username = "mutual1")
            val mutual2 = createTestUser(id = "mutual-2", username = "mutual2")
            val author = createTestUser(
                id = "author",
                followers = setOf("mutual-1", "mutual-2", "other")
            )
            
            userRepository.addUser(viewer)
            userRepository.addUser(mutual1)
            userRepository.addUser(mutual2)
            userRepository.addUser(author)
            
            val enricher = RelationshipDecorator(
                BaseEnricher(),
                userRepository,
                viewerIdProvider = { "viewer" }
            )
            
            val post = createTestPost(authorId = "author")
            val feedItem = FeedItem(post = post, author = author)
            val result = enricher.enrich(feedItem)
            
            assertNotNull(result.enrichedData)
            assertEquals(2, result.enrichedData!!.mutualFollowers.size)
        }
        
        @Test
        fun `stacked decorators apply in order`() {
            val mediaResolver = CdnMediaResolver("https://cdn.test.com")
            val liker = createTestUser(id = "liker")
            userRepository.addUser(liker)
            
            val enricher = EnrichmentPipelineBuilder()
                .withMediaResolution(mediaResolver)
                .withEngagementSummary(userRepository)
                .build()
            
            val author = createTestUser()
            val post = createTestPost(
                content = PostContent.ImageContent(listOf("img.jpg")),
                engagement = Engagement(likes = 10, likedBy = setOf("liker"))
            ).copy(type = PostType.IMAGE)
            
            val feedItem = FeedItem(post = post, author = author)
            val result = enricher.enrich(feedItem)
            
            assertNotNull(result.enrichedData)
            assertTrue(result.enrichedData!!.resolvedMediaUrls.isNotEmpty())
            assertNotNull(result.enrichedData!!.engagementSummary)
        }
        
        @Test
        fun `enrichment service processes batch`() {
            val enricher = BaseEnricher()
            val service = EnrichmentService(enricher)
            
            val author = createTestUser()
            val items = (1..5).map { i ->
                FeedItem(
                    post = createTestPost(id = "post-$i"),
                    author = author
                )
            }
            
            val results = service.enrichFeedItems(items)
            
            assertEquals(5, results.size)
        }
        
        @Test
        fun `trending decorator adds rank for trending posts`() {
            val trendingService = SimpleTrendingService()
            val posts = listOf(
                createTestPost(id = "trending", engagement = Engagement(likes = 1000))
            )
            trendingService.updateTrendingPosts(posts)
            
            val enricher = TrendingDecorator(BaseEnricher(), trendingService)
            
            val author = createTestUser()
            val trendingPost = posts.first()
            val feedItem = FeedItem(post = trendingPost, author = author)
            
            val result = enricher.enrich(feedItem)
            
            assertNotNull(result.enrichedData)
            assertEquals(1, result.enrichedData!!.trendingRank)
            assertEquals(FeedItem.FeedReason.TRENDING, result.reason)
        }
    }
    
    @Nested
    inner class CompositeCommentsTest {
        
        private lateinit var userRepository: InMemoryUserRepository
        private lateinit var commentRepository: InMemoryCommentRepository
        
        @BeforeEach
        fun setup() {
            userRepository = InMemoryUserRepository()
            commentRepository = InMemoryCommentRepository()
        }
        
        @Test
        fun `comment leaf has no children`() {
            val author = createTestUser()
            val comment = Comment(
                postId = "post-1",
                authorId = author.id,
                content = "Test comment"
            )
            
            val leaf = CommentLeaf(comment, author)
            
            assertEquals(0, leaf.getChildCount())
            assertEquals(1, leaf.getTotalCount())
            assertEquals("Test comment", leaf.content)
        }
        
        @Test
        fun `comment thread contains replies`() {
            val author = createTestUser()
            val replier = createTestUser(id = "replier", username = "replier")
            
            val rootComment = Comment(
                postId = "post-1",
                authorId = author.id,
                content = "Root comment"
            )
            
            val reply = Comment(
                postId = "post-1",
                authorId = replier.id,
                content = "Reply",
                parentId = rootComment.id
            )
            
            val thread = CommentThread(rootComment, author)
            thread.addReply(CommentLeaf(reply, replier))
            
            assertEquals(1, thread.getChildCount())
            assertEquals(2, thread.getTotalCount())
        }
        
        @Test
        fun `nested comment threads calculate total correctly`() {
            val user1 = createTestUser(id = "user-1")
            val user2 = createTestUser(id = "user-2")
            val user3 = createTestUser(id = "user-3")
            
            val root = Comment(postId = "post", authorId = "user-1", content = "Root")
            val reply1 = Comment(postId = "post", authorId = "user-2", content = "Reply 1", parentId = root.id)
            val reply2 = Comment(postId = "post", authorId = "user-3", content = "Reply 2", parentId = reply1.id)
            
            val innerThread = CommentThread(reply1, user2)
            innerThread.addReply(CommentLeaf(reply2, user3))
            
            val outerThread = CommentThread(root, user1)
            outerThread.addReply(innerThread)
            
            assertEquals(3, outerThread.getTotalCount())
            assertEquals(2, outerThread.getReplyDepth())
        }
        
        @Test
        fun `comment section sorts by different criteria`() {
            val author = createTestUser()
            val now = LocalDateTime.now()
            
            val oldPopular = Comment(
                id = "old-popular",
                postId = "post",
                authorId = author.id,
                content = "Old popular",
                likes = 100,
                createdAt = now.minusHours(5)
            )
            
            val newUnpopular = Comment(
                id = "new-unpopular",
                postId = "post",
                authorId = author.id,
                content = "New unpopular",
                likes = 5,
                createdAt = now
            )
            
            val section = CommentSection("post")
            section.addComment(CommentLeaf(oldPopular, author))
            section.addComment(CommentLeaf(newUnpopular, author))
            
            section.sortByNewest()
            assertEquals("new-unpopular", section.getComments().first().id)
            
            section.sortByPopular()
            assertEquals("old-popular", section.getComments().first().id)
            
            section.sortByOldest()
            assertEquals("old-popular", section.getComments().first().id)
        }
        
        @Test
        fun `comment service creates comments`() {
            val author = createTestUser()
            userRepository.addUser(author)
            
            val service = CommentService(commentRepository, userRepository)
            
            val result = service.createComment(
                postId = "post-1",
                authorId = author.id,
                content = "New comment"
            )
            
            assertNotNull(result)
            assertEquals("New comment", result!!.content)
        }
        
        @Test
        fun `comment service enforces max depth`() {
            val author = createTestUser()
            userRepository.addUser(author)
            
            // maxDepth = 1 means only root comments and 1 level of replies allowed
            val service = CommentService(commentRepository, userRepository, maxDepth = 1)
            
            val level0 = service.createComment("post", author.id, "Level 0")!!
            val comment0 = (level0 as CommentLeaf).getComment()
            commentRepository.saveComment(comment0)
            
            val level1 = service.createComment("post", author.id, "Level 1", comment0.id)!!
            val comment1 = (level1 as CommentLeaf).getComment()
            commentRepository.saveComment(comment1)
            
            // Level 2 reply should fail because parent (level1) has depth 1 = maxDepth
            val level2 = service.createComment("post", author.id, "Level 2", comment1.id)
            assertNull(level2)
        }
        
        @Test
        fun `comment service handles likes`() {
            val author = createTestUser()
            val liker = createTestUser(id = "liker")
            userRepository.addUser(author)
            userRepository.addUser(liker)
            
            val service = CommentService(commentRepository, userRepository)
            
            val result = service.createComment("post", author.id, "Comment")
            val comment = (result as CommentLeaf).getComment()
            commentRepository.saveComment(comment)
            
            val liked = service.likeComment(comment.id, liker.id)
            assertTrue(liked)
            
            val updatedComment = commentRepository.getComment(comment.id)!!
            assertEquals(1, updatedComment.likes)
            assertTrue(updatedComment.likedBy.contains(liker.id))
            
            val doubleLike = service.likeComment(comment.id, liker.id)
            assertFalse(doubleLike)
        }
        
        @Test
        fun `comment service paginates results`() {
            val author = createTestUser()
            userRepository.addUser(author)
            
            val service = CommentService(commentRepository, userRepository, defaultPageSize = 3)
            
            repeat(5) { i ->
                val comment = Comment(
                    postId = "post",
                    authorId = author.id,
                    content = "Comment $i"
                )
                commentRepository.saveComment(comment)
            }
            
            val page1 = service.getCommentsForPost("post", pageSize = 3)
            assertEquals(3, page1.comments.size)
            assertTrue(page1.hasMore)
            assertNotNull(page1.nextCursor)
            
            val page2 = service.getCommentsForPost("post", pageSize = 3, cursor = page1.nextCursor)
            assertEquals(2, page2.comments.size)
            assertFalse(page2.hasMore)
        }
        
        @Test
        fun `comment tree builder creates hierarchy`() {
            val author = createTestUser()
            userRepository.addUser(author)
            
            val builder = CommentTreeBuilder(userRepository)
            
            val root1 = Comment(id = "root1", postId = "post", authorId = author.id, content = "Root 1")
            val root2 = Comment(id = "root2", postId = "post", authorId = author.id, content = "Root 2")
            val reply1 = Comment(id = "reply1", postId = "post", authorId = author.id, content = "Reply 1", parentId = "root1")
            
            val comments = listOf(root1, root2, reply1)
            val section = builder.buildSection("post", comments)
            
            assertEquals(2, section.getComments().size)
            
            val root1Thread = section.findComment("root1") as CommentThread
            assertEquals(1, root1Thread.getChildCount())
        }
        
        @Test
        fun `comment visitor counts correctly`() {
            val author = createTestUser()
            
            val root = Comment(postId = "post", authorId = author.id, content = "Root", likes = 5)
            val reply = Comment(postId = "post", authorId = author.id, content = "Reply", likes = 3)
            
            val thread = CommentThread(root, author)
            thread.addReply(CommentLeaf(reply, author))
            
            val section = CommentSection("post")
            section.addComment(thread)
            
            val counter = CommentCounterVisitor()
            section.accept(counter)
            
            assertEquals(2, counter.totalComments)
            assertEquals(8, counter.totalLikes)
            assertEquals(1, counter.maxDepth)
        }
        
        @Test
        fun `comment search finds matching content`() {
            val author = createTestUser()
            
            val comment1 = Comment(postId = "post", authorId = author.id, content = "Hello world")
            val comment2 = Comment(postId = "post", authorId = author.id, content = "Goodbye world")
            val comment3 = Comment(postId = "post", authorId = author.id, content = "No match here")
            
            val section = CommentSection("post")
            section.addComment(CommentLeaf(comment1, author))
            section.addComment(CommentLeaf(comment2, author))
            section.addComment(CommentLeaf(comment3, author))
            
            val searcher = CommentSearchVisitor("world")
            section.accept(searcher)
            
            assertEquals(2, searcher.matchingComments.size)
        }
        
        @Test
        fun `flatten replies returns linear list with depth`() {
            val author = createTestUser()
            
            val root = Comment(id = "root", postId = "post", authorId = author.id, content = "Root")
            val reply1 = Comment(id = "r1", postId = "post", authorId = author.id, content = "R1", parentId = "root")
            val reply2 = Comment(id = "r2", postId = "post", authorId = author.id, content = "R2", parentId = "r1")
            
            val innerThread = CommentThread(reply1, author)
            innerThread.addReply(CommentLeaf(reply2, author))
            
            val thread = CommentThread(root, author)
            thread.addReply(innerThread)
            
            val flattened = thread.flattenReplies()
            
            assertEquals(3, flattened.size)
            assertEquals(0, flattened[0].depth)
            assertEquals(1, flattened[1].depth)
            assertEquals(2, flattened[2].depth)
        }
    }
    
    @Nested
    inner class ModelTest {
        
        @Test
        fun `user follows and unfollows correctly`() {
            var user = createTestUser(id = "user")
            
            user = user.follow("target")
            assertTrue(user.isFollowing("target"))
            
            user = user.unfollow("target")
            assertFalse(user.isFollowing("target"))
        }
        
        @Test
        fun `user celebrity detection works`() {
            val regularUser = createTestUser(followers = (1..100).map { "f$it" }.toSet())
            val celebrity = createTestUser(followers = (1..15000).map { "f$it" }.toSet())
            
            assertFalse(regularUser.isCelebrity())
            assertTrue(celebrity.isCelebrity())
        }
        
        @Test
        fun `engagement like and unlike work`() {
            var engagement = Engagement()
            
            engagement = engagement.like("user-1")
            assertEquals(1, engagement.likes)
            assertTrue(engagement.isLikedBy("user-1"))
            
            engagement = engagement.like("user-1")
            assertEquals(1, engagement.likes)
            
            engagement = engagement.unlike("user-1")
            assertEquals(0, engagement.likes)
            assertFalse(engagement.isLikedBy("user-1"))
        }
        
        @Test
        fun `post content types work correctly`() {
            val textContent = PostContent.TextContent(
                text = "Hello #world @user",
                mentions = listOf("user"),
                hashtags = listOf("world")
            )
            assertEquals("Hello #world @user", textContent.rawText)
            
            val pollContent = PostContent.PollContent(
                question = "Favorite color?",
                options = listOf(
                    PollOption(text = "Red"),
                    PollOption(text = "Blue", votes = 5)
                ),
                expiresAt = LocalDateTime.now().plusDays(1)
            )
            assertEquals(5, pollContent.totalVotes())
            assertFalse(pollContent.isExpired())
        }
        
        @Test
        fun `poll voting works`() {
            var option = PollOption(text = "Option A")
            
            option = option.addVote("user-1")
            assertEquals(1, option.votes)
            assertTrue(option.hasVotedBy("user-1"))
            
            option = option.addVote("user-2")
            assertEquals(2, option.votes)
        }
        
        @Test
        fun `comment like and unlike work`() {
            var comment = Comment(
                postId = "post",
                authorId = "author",
                content = "Test"
            )
            
            comment = comment.like("liker")
            assertEquals(1, comment.likes)
            
            comment = comment.unlike("liker")
            assertEquals(0, comment.likes)
        }
    }
}
