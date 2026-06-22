package com.cicdassistant.mapper;

import com.cicdassistant.entity.CompareFinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CompareFindingMapper {
    List<CompareFinding> findByTargetId(@Param("targetId") Long targetId);
    int insert(CompareFinding f);
    int deleteByTargetId(@Param("targetId") Long targetId);
}
