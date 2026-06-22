package com.cicdassistant.mapper;

import com.cicdassistant.entity.CompareTarget;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CompareTargetMapper {
    List<CompareTarget> findByTaskId(@Param("taskId") Long taskId);
    CompareTarget findById(@Param("id") Long id);
    int insert(CompareTarget t);
    int update(CompareTarget t);
    int deleteByTaskId(@Param("taskId") Long taskId);
}
