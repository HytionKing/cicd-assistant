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
    int insert(Task task);
    int update(Task task);
    int deleteById(@Param("id") Long id);
}
