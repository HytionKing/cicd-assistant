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
    TaskModule findPlaceholder(@Param("taskId") Long taskId, @Param("branch") String branch);
    int insert(TaskModule m);
    int update(TaskModule m);
    int deleteById(@Param("id") Long id);
    int deleteByTaskId(@Param("taskId") Long taskId);
}
