package com.cicdassistant.mapper;

import com.cicdassistant.entity.TaskModule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskModuleMapper {
    List<TaskModule> findByTaskId(@Param("taskId") Long taskId);
    TaskModule findById(@Param("id") Long id);
    List<TaskModule> findAliveCandidates();
    int insert(TaskModule m);
    int update(TaskModule m);
    int deleteByTaskId(@Param("taskId") Long taskId);
}
