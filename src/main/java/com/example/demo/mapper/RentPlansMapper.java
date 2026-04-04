package com.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.domain.entity.RentPlans;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RentPlansMapper extends BaseMapper<RentPlans> {

    void batchUpdateReceivedAmount(@Param("list") List<RentPlans> list);
}
