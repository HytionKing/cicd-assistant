package com.cicdassistant.mapper;

import com.cicdassistant.entity.CompareContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CompareContextMapper {
    List<CompareContext> findAll();
    List<CompareContext> findApplicable(@Param("repoId") Long repoId);  // global + repo-specific, enabled only
    CompareContext findById(@Param("id") Long id);
    int insert(CompareContext c);
    int update(CompareContext c);
    int deleteById(@Param("id") Long id);
}
