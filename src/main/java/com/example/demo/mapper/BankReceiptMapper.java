package com.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.domain.entity.BankReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BankReceiptMapper extends BaseMapper<BankReceipt> {

    void batchUpdateUsedAmount(@Param("list") List<BankReceipt> list);
}
