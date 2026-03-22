package com.systemdesign.socialmediafeed.approach_03_composite_comments

import com.systemdesign.socialmediafeed.common.*
import java.time.LocalDateTime
import java.util.UUID

/**
 * Approach 3: Composite Pattern for Threaded Comments
 * 
 * Comments form a tree structure where each comment can have replies.
 * The composite pattern allows uniform treatment of individual comments
 * and comment threads.
 * 
 * Pattern: Composite Pattern
 * 
 * Trade-offs:
 * + Unified interface for leaves (comments) and composites (threads)
 * + Natural representation of nested comment structure
 * + Easy to traverse and render comment trees
 * + Pagination can be applied at any level
 * - Deep nesting can become hard to display
 * - Need to limit reply depth in practice
 * 
 * When to use:
 * - When comments can have unlimited nested replies
 * - When operations need to work on both individual comments and threads
 * - When displaying collapsed/expanded thread views
 * 
 * Extensibility:
 * - New comment types: Extend CommentNode
 * - New aggregations: Add methods to CommentComponent interface
 * - Moderation: Add status field and filter in traversal
 */

/**
 * Leaf node representing a single comment without replies
 */
class CommentLeaf(
    private val comment: Comment,
    private val author: User
) : CommentComponent {
    
    override val id: String = comment.id
    override val content: String = comment.content
    override val authorId: String = comment.authorId
    override val likes: Int = comment.likes
    override val createdAt: LocalDateTime = comment.createdAt
    
    val authorName: String = author.displayName
    val authorAvatar: String? = author.avatarUrl
    val isAuthorVerified: Boolean = author.isVerified
    
    fun getComment(): Comment = comment
    fun getAuthor(): User = author
    
    override fun getChildCount(): Int = 0
    override fun getTotalCount(): Int = 1
    
    fun isLikedBy(userId: String): Boolean = comment.likedBy.contains(userId)
    
    fun like(userId: String): CommentLeaf {
        return CommentLeaf(comment.like(userId), author)
    }
    
    fun unlike(userId: String): CommentLeaf {
        return CommentLeaf(comment.unlike(userId), author)
    }
}

/**
 * Composite node representing a comment with replies (thread)
 */
class CommentThread(
    private val rootComment: Comment,
    private val author: User,
    private val replies: MutableList<CommentComponent> = mutableListOf(),
    private var hasMoreReplies: Boolean = false,
    private var totalReplyCount: Int = 0
) : CommentComponent {
    
    override val id: String = rootComment.id
    override val content: String = rootComment.content
    override val authorId: String = rootComment.authorId
    override val likes: Int = rootComment.likes
    override val createdAt: LocalDateTime = rootComment.createdAt
    
    val authorName: String = author.displayName
    val authorAvatar: String? = author.avatarUrl
    val isAuthorVerified: Boolean = author.isVerified
    
    fun getRootComment(): Comment = rootComment
    fun getAuthor(): User = author
    fun getReplies(): List<CommentComponent> = replies.toList()
    fun hasMoreReplies(): Boolean = hasMoreReplies
    fun getTotalReplyCount(): Int = totalReplyCount
    
    override fun getChildCount(): Int = replies.size
    
    override fun getTotalCount(): Int {
        return 1 + replies.sumOf { it.getTotalCount() }
    }
    
    fun addReply(reply: CommentComponent) {
        replies.add(reply)
        totalReplyCount++
    }
    
    fun removeReply(replyId: String): Boolean {
        val removed = replies.removeIf { it.id == replyId }
        if (removed) totalReplyCount--
        return removed
    }
    
    fun setHasMoreReplies(hasMore: Boolean, total: Int) {
        hasMoreReplies = hasMore
        totalReplyCount = total
    }
    
    fun findReply(replyId: String): CommentComponent? {
        for (reply in replies) {
            if (reply.id == replyId) return reply
            if (reply is CommentThread) {
                val found = reply.findReply(replyId)
                if (found != null) return found
            }
        }
        return null
    }
    
    fun getReplyDepth(): Int {
        if (replies.isEmpty()) return 0
        return 1 + replies.filterIsInstance<CommentThread>().maxOfOrNull { it.getReplyDepth() }.let { it ?: 0 }
    }
    
    fun flattenReplies(maxDepth: Int = Int.MAX_VALUE, currentDepth: Int = 0): List<FlattenedComment> {
        val result = mutableListOf<FlattenedComment>()
        result.add(FlattenedComment(rootComment, author, currentDepth))
        
        if (currentDepth < maxDepth) {
            for (reply in replies) {
                when (reply) {
                    is CommentLeaf -> {
                        result.add(FlattenedComment(reply.getComment(), reply.getAuthor(), currentDepth + 1))
                    }
                    is CommentThread -> {
                        result.addAll(reply.flattenReplies(maxDepth, currentDepth + 1))
                    }
                }
            }
        }
        
        return result
    }
    
    fun like(userId: String): CommentThread {
        return CommentThread(
            rootComment.like(userId),
            author,
            replies,
            hasMoreReplies,
            totalReplyCount
        )
    }
}

/**
 * Flattened comment for linear display
 */
data class FlattenedComment(
    val comment: Comment,
    val author: User,
    val depth: Int
)

/**
 * Comment section containing all top-level comments for a post
 */
class CommentSection(
    val postId: String,
    private val comments: MutableList<CommentComponent> = mutableListOf(),
    private var totalCount: Int = 0,
    private var hasMore: Boolean = false
) {
    fun getComments(): List<CommentComponent> = comments.toList()
    fun getTotalCount(): Int = totalCount
    fun hasMoreComments(): Boolean = hasMore
    
    fun addComment(comment: CommentComponent) {
        comments.add(0, comment)
        totalCount++
    }
    
    fun addComments(newComments: List<CommentComponent>) {
        comments.addAll(newComments)
    }
    
    fun removeComment(commentId: String): Boolean {
        val removed = comments.removeIf { it.id == commentId }
        if (removed) totalCount--
        return removed
    }
    
    fun findComment(commentId: String): CommentComponent? {
        for (comment in comments) {
            if (comment.id == commentId) return comment
            if (comment is CommentThread) {
                val found = comment.findReply(commentId)
                if (found != null) return found
            }
        }
        return null
    }
    
    fun setMetadata(total: Int, hasMore: Boolean) {
        this.totalCount = total
        this.hasMore = hasMore
    }
    
    fun getDisplayedCount(): Int = comments.sumOf { it.getTotalCount() }
    
    fun sortByNewest() {
        comments.sortByDescending { it.createdAt }
    }
    
    fun sortByPopular() {
        comments.sortByDescending { it.likes }
    }
    
    fun sortByOldest() {
        comments.sortBy { it.createdAt }
    }
}

/**
 * Paginated result for comment queries
 */
data class CommentPage(
    val comments: List<CommentComponent>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val totalCount: Int
)

/**
 * Service for managing comments with pagination
 */
class CommentService(
    private val commentRepository: CommentRepository,
    private val userRepository: com.systemdesign.socialmediafeed.approach_02_decorator_enrichment.UserRepository,
    private val maxDepth: Int = 5,
    private val defaultPageSize: Int = 20
) {
    private val observers = mutableListOf<CommentObserver>()
    
    fun addObserver(observer: CommentObserver) {
        observers.add(observer)
    }
    
    fun createComment(
        postId: String,
        authorId: String,
        content: String,
        parentId: String? = null
    ): CommentComponent? {
        val author = userRepository.getUser(authorId) ?: return null
        
        if (parentId != null) {
            val depth = commentRepository.getCommentDepth(parentId)
            if (depth >= maxDepth) {
                return null
            }
        }
        
        val comment = Comment(
            postId = postId,
            authorId = authorId,
            content = content,
            parentId = parentId,
            mentions = extractMentions(content)
        )
        
        commentRepository.saveComment(comment)
        
        val component = CommentLeaf(comment, author)
        observers.forEach { it.onCommentCreated(comment) }
        
        return component
    }
    
    fun getCommentsForPost(
        postId: String,
        pageSize: Int = defaultPageSize,
        cursor: String? = null,
        sortBy: SortOrder = SortOrder.NEWEST
    ): CommentPage {
        val allComments = commentRepository.getTopLevelComments(postId)
        val sortedComments = when (sortBy) {
            SortOrder.NEWEST -> allComments.sortedByDescending { it.createdAt }
            SortOrder.OLDEST -> allComments.sortedBy { it.createdAt }
            SortOrder.POPULAR -> allComments.sortedByDescending { it.likes }
        }
        
        val startIndex = cursor?.toIntOrNull() ?: 0
        val pageComments = sortedComments.drop(startIndex).take(pageSize)
        
        val components = pageComments.mapNotNull { comment ->
            buildCommentTree(comment, initialRepliesLimit = 3)
        }
        
        val hasMore = startIndex + pageSize < sortedComments.size
        val nextCursor = if (hasMore) (startIndex + pageSize).toString() else null
        
        return CommentPage(
            comments = components,
            nextCursor = nextCursor,
            hasMore = hasMore,
            totalCount = sortedComments.size
        )
    }
    
    fun getReplies(
        commentId: String,
        pageSize: Int = defaultPageSize,
        cursor: String? = null
    ): CommentPage {
        val replies = commentRepository.getReplies(commentId)
        val sortedReplies = replies.sortedBy { it.createdAt }
        
        val startIndex = cursor?.toIntOrNull() ?: 0
        val pageReplies = sortedReplies.drop(startIndex).take(pageSize)
        
        val components = pageReplies.mapNotNull { reply ->
            buildCommentTree(reply, initialRepliesLimit = 2)
        }
        
        val hasMore = startIndex + pageSize < sortedReplies.size
        val nextCursor = if (hasMore) (startIndex + pageSize).toString() else null
        
        return CommentPage(
            comments = components,
            nextCursor = nextCursor,
            hasMore = hasMore,
            totalCount = sortedReplies.size
        )
    }
    
    private fun buildCommentTree(comment: Comment, initialRepliesLimit: Int): CommentComponent? {
        val author = userRepository.getUser(comment.authorId) ?: return null
        
        val replies = commentRepository.getReplies(comment.id)
        
        if (replies.isEmpty()) {
            return CommentLeaf(comment, author)
        }
        
        val thread = CommentThread(comment, author)
        
        val initialReplies = replies
            .sortedBy { it.createdAt }
            .take(initialRepliesLimit)
        
        for (reply in initialReplies) {
            val replyComponent = buildCommentTree(reply, initialRepliesLimit = 1)
            if (replyComponent != null) {
                thread.addReply(replyComponent)
            }
        }
        
        thread.setHasMoreReplies(
            hasMore = replies.size > initialRepliesLimit,
            total = replies.size
        )
        
        return thread
    }
    
    fun likeComment(commentId: String, userId: String): Boolean {
        val comment = commentRepository.getComment(commentId) ?: return false
        
        if (comment.likedBy.contains(userId)) return false
        
        val updated = comment.like(userId)
        commentRepository.updateComment(updated)
        
        observers.forEach { it.onCommentLiked(commentId, userId) }
        return true
    }
    
    fun unlikeComment(commentId: String, userId: String): Boolean {
        val comment = commentRepository.getComment(commentId) ?: return false
        
        if (!comment.likedBy.contains(userId)) return false
        
        val updated = comment.unlike(userId)
        commentRepository.updateComment(updated)
        
        return true
    }
    
    fun deleteComment(commentId: String, userId: String): Boolean {
        val comment = commentRepository.getComment(commentId) ?: return false
        
        if (comment.authorId != userId) return false
        
        val updated = comment.copy(isDeleted = true, content = "[deleted]")
        commentRepository.updateComment(updated)
        
        observers.forEach { it.onCommentDeleted(commentId) }
        return true
    }
    
    fun getCommentCount(postId: String): Int {
        return commentRepository.getCommentCount(postId)
    }
    
    private fun extractMentions(content: String): List<String> {
        val mentionPattern = Regex("@(\\w+)")
        return mentionPattern.findAll(content)
            .map { it.groupValues[1] }
            .toList()
    }
    
    enum class SortOrder {
        NEWEST, OLDEST, POPULAR
    }
}

/** Observer interface for comment events */
interface CommentObserver {
    fun onCommentCreated(comment: Comment)
    fun onCommentLiked(commentId: String, userId: String)
    fun onCommentDeleted(commentId: String)
}

/** Repository interface for comment persistence */
interface CommentRepository {
    fun saveComment(comment: Comment)
    fun getComment(commentId: String): Comment?
    fun updateComment(comment: Comment)
    fun deleteComment(commentId: String)
    fun getTopLevelComments(postId: String): List<Comment>
    fun getReplies(parentId: String): List<Comment>
    fun getCommentCount(postId: String): Int
    fun getCommentDepth(commentId: String): Int
}

/** In-memory comment repository implementation */
class InMemoryCommentRepository : CommentRepository {
    private val comments = mutableMapOf<String, Comment>()
    
    override fun saveComment(comment: Comment) {
        comments[comment.id] = comment
    }
    
    override fun getComment(commentId: String): Comment? = comments[commentId]
    
    override fun updateComment(comment: Comment) {
        comments[comment.id] = comment
    }
    
    override fun deleteComment(commentId: String) {
        comments.remove(commentId)
    }
    
    override fun getTopLevelComments(postId: String): List<Comment> {
        return comments.values
            .filter { it.postId == postId && it.parentId == null && !it.isDeleted }
    }
    
    override fun getReplies(parentId: String): List<Comment> {
        return comments.values
            .filter { it.parentId == parentId && !it.isDeleted }
    }
    
    override fun getCommentCount(postId: String): Int {
        return comments.values.count { it.postId == postId && !it.isDeleted }
    }
    
    override fun getCommentDepth(commentId: String): Int {
        var depth = 0
        var current = comments[commentId]
        
        while (current?.parentId != null) {
            depth++
            current = comments[current.parentId]
        }
        
        return depth
    }
}

/**
 * Comment tree builder for constructing comment hierarchies
 */
class CommentTreeBuilder(
    private val userRepository: com.systemdesign.socialmediafeed.approach_02_decorator_enrichment.UserRepository
) {
    fun buildSection(postId: String, comments: List<Comment>): CommentSection {
        val section = CommentSection(postId)
        
        val topLevel = comments.filter { it.parentId == null }
        val byParent = comments.filter { it.parentId != null }.groupBy { it.parentId }
        
        for (comment in topLevel.sortedByDescending { it.createdAt }) {
            val tree = buildTree(comment, byParent)
            if (tree != null) {
                section.addComment(tree)
            }
        }
        
        return section
    }
    
    private fun buildTree(
        comment: Comment,
        byParent: Map<String?, List<Comment>>
    ): CommentComponent? {
        val author = userRepository.getUser(comment.authorId) ?: return null
        val replies = byParent[comment.id] ?: emptyList()
        
        if (replies.isEmpty()) {
            return CommentLeaf(comment, author)
        }
        
        val thread = CommentThread(comment, author)
        
        for (reply in replies.sortedBy { it.createdAt }) {
            val replyTree = buildTree(reply, byParent)
            if (replyTree != null) {
                thread.addReply(replyTree)
            }
        }
        
        return thread
    }
}

/**
 * Visitor pattern for comment tree traversal
 */
interface CommentVisitor {
    fun visitLeaf(leaf: CommentLeaf)
    fun visitThread(thread: CommentThread)
}

/**
 * Comment counter visitor
 */
class CommentCounterVisitor : CommentVisitor {
    var totalComments = 0
        private set
    var totalLikes = 0
        private set
    var maxDepth = 0
        private set
    private var currentDepth = 0
    
    override fun visitLeaf(leaf: CommentLeaf) {
        totalComments++
        totalLikes += leaf.likes
        maxDepth = maxOf(maxDepth, currentDepth)
    }
    
    override fun visitThread(thread: CommentThread) {
        totalComments++
        totalLikes += thread.likes
        maxDepth = maxOf(maxDepth, currentDepth)
        
        currentDepth++
        for (reply in thread.getReplies()) {
            when (reply) {
                is CommentLeaf -> visitLeaf(reply)
                is CommentThread -> visitThread(reply)
            }
        }
        currentDepth--
    }
}

/**
 * Comment search visitor
 */
class CommentSearchVisitor(
    private val searchText: String
) : CommentVisitor {
    val matchingComments = mutableListOf<CommentComponent>()
    
    override fun visitLeaf(leaf: CommentLeaf) {
        if (leaf.content.contains(searchText, ignoreCase = true)) {
            matchingComments.add(leaf)
        }
    }
    
    override fun visitThread(thread: CommentThread) {
        if (thread.content.contains(searchText, ignoreCase = true)) {
            matchingComments.add(thread)
        }
        
        for (reply in thread.getReplies()) {
            when (reply) {
                is CommentLeaf -> visitLeaf(reply)
                is CommentThread -> visitThread(reply)
            }
        }
    }
}

fun CommentComponent.accept(visitor: CommentVisitor) {
    when (this) {
        is CommentLeaf -> visitor.visitLeaf(this)
        is CommentThread -> visitor.visitThread(this)
    }
}

fun CommentSection.accept(visitor: CommentVisitor) {
    for (comment in getComments()) {
        comment.accept(visitor)
    }
}
