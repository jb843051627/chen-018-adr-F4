package com.fc.v2.service;

import com.fc.v2.model.auto.TAdrReviewBill;
import com.fc.v2.model.custom.adr.SignAuditVo;

/**
 * 评价结论逐级复核签认单 Service接口（approval-chain 形状：三道口逐级签批，无增删改查入口）
 *
 * 三道口：县初核(0)、市复核(1)、省审定(2)，一道一道往上走，每道落几名由本道自己定，
 * 并署办法不给人随口改。落名只有一个入口——这里这一对签认与打回（连同同名重载）；
 * 屏面上不再另设能签、能退、能改署名的第二处，当前道次也不预先写在页面上等人回填。
 * 本口已落名数由这一对方法顺着签迹一笔一笔点出来交回——界面摆着的数、经办人自己记住的数，
 * 都当不得依据；签没签成只看它交回什么。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITAdrReviewBillService {

    /**
     * 按主键回查单据。回来的那份，当前道次、本口已落名数、当下算哪一步，
     * 一律由系统顺着签迹当场点算——库里栏上若被人写花，以点出来的为准。
     */
    TAdrReviewBill selectTAdrReviewBillById(Long id);

    /**
     * 签认一票：落在当前所在那一道。本口并署办法定成两名点齐的，落满两名才算过，
     * 落了一名照旧候名；定成任一人即过的，头一名落下就算过。过口自然进到下一道。
     * 已核讫的单锁死；被拒（查无此单、落名人空着、已核讫）返回 null。
     */
    TAdrReviewBill approve(Long id, String approver, String comment);

    /**
     * 换一种签法（指明道次落名），同名底下并排添的重载。
     * 指明的道次与单当前所在道对不上，这一笔视同没落——县初核还空着市复核替它落名、
     * 省审定跨过市复核直接落章，都落不进去；被拒返回 null。
     */
    TAdrReviewBill approve(Long id, Integer nodeNo, String approver, String comment);

    /**
     * 打回：哪一道写下打回，这单就钉死在该道——事后既不往上加签，也不回头改已落的签，
     * 旁道没有替他接下去的份。此前各道攒下的署名整批退档，这单退回县初核那一道重新起签；
     * 退档的那一批与新补的那一批各归各堆，不摆进同一列里给人看。
     * 已核讫、已打回（还没重新起签）的单打回不得；被拒返回 null。
     */
    TAdrReviewBill reject(Long id, String approver, String comment);

    /**
     * 换一种打回法（指明道次），同名底下并排添的重载。
     * 指明的道次与单当前所在道对不上，这一笔视同没落；被拒返回 null。
     */
    TAdrReviewBill reject(Long id, Integer nodeNo, String approver, String comment);

    /**
     * 退回上一环节：签名不动。如今退回只走打回那一对（整批退档、退回县初核重新起签），
     * 没有"往回退一格"这一着——已落的签一句改不得，也抽不回来。一律被拒返回 null。
     */
    TAdrReviewBill rollback(Long id, String comment);

    /**
     * 倒读清点：这一件最终定稿的那一版，当日留在签认档里的记载从省审定一路数回县初核，
     * 各级签过的姓名逐条清点，点出来的总数必须与当天屏上那一份对得拢，
     * 单拿出任意一道也经得起复核；退档沉下的旧批不算在定稿那一版里。
     */
    SignAuditVo auditSigns(Long id);
}
