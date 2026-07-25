package com.cicdassistant.mapper;

import com.cicdassistant.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskMapper {
    List<Task> findAll();
    List<Task> findPage(@Param("offset") int offset, @Param("size") int size);
    int count();
    Task findById(@Param("id") Long id);
    /** 找该仓库下所有未终态的任务（PENDING / RUNNING），用来做同分支冲突检测 */
    List<Task> findActiveByRepo(@Param("repoId") Long repoId);
    /** 带筛选的分页；参数为 null / 空列表表示不过滤。分支过滤是 OR 匹配（命中任意一个即算） */
    List<Task> findPageFiltered(@Param("repoIds") List<Long> repoIds,
                                @Param("branches") List<String> branches,
                                @Param("status") String status,
                                @Param("createdFrom") String createdFrom,
                                @Param("createdTo") String createdTo,
                                @Param("offset") int offset,
                                @Param("size") int size);
    int countFiltered(@Param("repoIds") List<Long> repoIds,
                      @Param("branches") List<String> branches,
                      @Param("status") String status,
                      @Param("createdFrom") String createdFrom,
                      @Param("createdTo") String createdTo);
    int insert(Task task);
    int update(Task task);
    int deleteById(@Param("id") Long id);
}
