package org.javaup.controller;


import jakarta.annotation.Resource;
import org.javaup.dto.Result;
import org.javaup.entity.BlogComments;
import org.javaup.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 博客评论api
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    @GetMapping("/of/blog")
    public Result queryCommentsByBlogId(
            @RequestParam("blogId") Long blogId,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        return blogCommentsService.queryCommentsByBlogId(blogId, current);
    }

    @PostMapping
    public Result saveComment(@RequestBody BlogComments comment) {
        return blogCommentsService.saveComment(comment);
    }
}
