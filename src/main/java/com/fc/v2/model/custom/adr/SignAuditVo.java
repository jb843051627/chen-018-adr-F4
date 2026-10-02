package com.fc.v2.model.custom.adr;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * 签认单倒读清点结果：一件最终定稿的结论，当日留在签认档里的记载要能从省审定一路
 * 数回县初核，各级签过的姓名逐条清点，点出来的总数必须与当天屏上那一份对得拢，
 * 单拿出任意一道也经得起复核；退档沉下的旧批不算在定稿那一版里。
 *
 * @author fuce
 * @date 2026-10-02
 */
@ApiModel(value = "SignAuditVo", description = "签认单倒读清点结果")
public class SignAuditVo {

    @ApiModelProperty(value = "倒读是否对得拢（true=这一件定稿经得起清点）")
    private boolean passed;

    @ApiModelProperty(value = "判定一句话（对不上时点出伤在哪一道）")
    private String message;

    @ApiModelProperty(value = "定稿那一版在册签认总名数（三道合计，退档的不计）")
    private int totalCount;

    @ApiModelProperty(value = "县初核那一道点出的名数")
    private int countyCount;

    @ApiModelProperty(value = "市复核那一道点出的名数")
    private int cityCount;

    @ApiModelProperty(value = "省审定那一道点出的名数")
    private int provinceCount;

    public SignAuditVo() {
    }

    public SignAuditVo(boolean passed, String message, int totalCount,
                       int countyCount, int cityCount, int provinceCount) {
        this.passed = passed;
        this.message = message;
        this.totalCount = totalCount;
        this.countyCount = countyCount;
        this.cityCount = cityCount;
        this.provinceCount = provinceCount;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getCountyCount() {
        return countyCount;
    }

    public void setCountyCount(int countyCount) {
        this.countyCount = countyCount;
    }

    public int getCityCount() {
        return cityCount;
    }

    public void setCityCount(int cityCount) {
        this.cityCount = cityCount;
    }

    public int getProvinceCount() {
        return provinceCount;
    }

    public void setProvinceCount(int provinceCount) {
        this.provinceCount = provinceCount;
    }
}
