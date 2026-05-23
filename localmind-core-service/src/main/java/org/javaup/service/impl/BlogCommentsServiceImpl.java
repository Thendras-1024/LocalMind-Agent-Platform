package org.javaup.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.javaup.dto.Result;
import org.javaup.dto.UserDTO;
import org.javaup.entity.Blog;
import org.javaup.entity.BlogComments;
import org.javaup.mapper.BlogCommentsMapper;
import org.javaup.service.IBlogService;
import org.javaup.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.utils.SystemConstants;
import org.javaup.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 博客评论接口实现
 * @author: 阿星不是程序员
 **/
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IBlogService blogService;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public Result queryCommentsByBlogId(Long blogId, Integer current) {
        if (blogId == null) {
            return Result.fail("笔记id不能为空");
        }
        Page<BlogComments> page = query()
                .eq("blog_id", blogId)
                .eq("status", 0)
                .orderByAsc("parent_id")
                .orderByAsc("create_time")
                .page(new Page<>(current == null || current < 1 ? 1 : current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result saveComment(BlogComments comment) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (comment == null || comment.getBlogId() == null) {
            return Result.fail("笔记id不能为空");
        }
        if (StrUtil.isBlank(comment.getContent())) {
            return Result.fail("评论内容不能为空");
        }
        Blog blog = blogService.getById(comment.getBlogId());
        if (Objects.isNull(blog)) {
            return Result.fail("笔记不存在");
        }

        comment.setId(snowflakeIdGenerator.nextId());
        comment.setUserId(user.getId());
        comment.setParentId(comment.getParentId() == null ? 0L : comment.getParentId());
        comment.setAnswerId(comment.getAnswerId() == null ? 0L : comment.getAnswerId());
        comment.setLiked(0);
        comment.setStatus(false);
        boolean saved = save(comment);
        if (!saved) {
            return Result.fail("评论发布失败");
        }
        blogService.update().setSql("comments = IFNULL(comments, 0) + 1").eq("id", comment.getBlogId()).update();
        return Result.ok(comment.getId());
    }
}
