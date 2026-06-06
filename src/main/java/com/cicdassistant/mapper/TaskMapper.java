package com.cicdassistant.mapper;

import com.cicdassistant.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskMapper {
    List<Task> findAll();
    Task findById(@Param("id") Long id);
    int insert(Task task);
    int update(Task task);
    int deleteById(@Param("id") Long id);
}
