package com.bob.angularspringbootfullstack.report;

import com.bob.angularspringbootfullstack.model.Customer;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.List;

@Slf4j
public class CustomerReport {
    private final List<Customer> customers;
    private final XSSFWorkbook workbook;
    private final XSSFSheet sheet;

    public CustomerReport(List<Customer> customers) {
        this.customers = customers;
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Customers");
    }
}
