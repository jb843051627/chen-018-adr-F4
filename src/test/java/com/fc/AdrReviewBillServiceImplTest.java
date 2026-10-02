package com.fc;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TAdrReviewBill;
import com.fc.v2.model.auto.TAdrReviewSign;
import com.fc.v2.model.custom.adr.SignAuditVo;
import com.fc.v2.service.impl.TAdrReviewBillServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评价结论逐级复核签认单集成测试——去年夏天那桩返工立下的规矩：
 * 三道口（县初核/市复核/省审定）一道一道往上走，每道落几名由本道自己定；
 * 已落名数顺着签迹点算，栏里敲的不算；不许抢步；打回整批退档、退回县初核重新起签，
 * 两批各归各堆；两个姓名各归各；定稿那版从省审定一路数回县初核要清点得拢。
 */
class AdrReviewBillServiceImplTest extends AdrBookletTestBase {

    private TAdrReviewBillServiceImpl billService;

    @BeforeEach
    void setUpBill() throws Exception {
        this.billService = new TAdrReviewBillServiceImpl();
        injectField(this.billService, "adrReviewBillMapper", this.reviewBillMapper);
        injectField(this.billService, "adrReviewSignMapper", this.reviewSignMapper);
    }

    /** 立一单：县初核那一道空着手起，立单经手人落定 */
    private TAdrReviewBill seedBill(String billNo) {
        TAdrReviewBill bill = new TAdrReviewBill();
        bill.setBillNo(billNo);
        bill.setNodeNo(0);
        bill.setSignMode(0);
        bill.setNeedCount(1);
        bill.setSignCount(0);
        bill.setStatus(0);
        bill.setDelFlag(0);
        bill.setCreateBy("立单人");
        reviewBillMapper.insert(bill);
        return bill;
    }

    /** 这一单全部签迹（含退档沉下的），按落库先后 */
    private List<TAdrReviewSign> allTraces(Long billId) {
        return reviewSignMapper.selectList(new QueryWrapper<TAdrReviewSign>()
                .eq("bill_id", billId).orderByAsc("id"));
    }

    /** 现行那一批签迹（屏上摆的那一列） */
    private List<TAdrReviewSign> liveTraces(Long billId) {
        return reviewSignMapper.selectList(new QueryWrapper<TAdrReviewSign>()
                .eq("bill_id", billId).eq("pile", 0).orderByAsc("id"));
    }

    private TAdrReviewBill stored(Long id) {
        return reviewBillMapper.selectById(id);
    }

    /** 县、市各落一名，把单送到省审定那一道 */
    private void walkToProvince(Long id) {
        assertNotNull(billService.approve(id, "县某", "县初核讫"));
        assertNotNull(billService.approve(id, "市某", "市复核讫"));
    }

    /** 一路走到定稿：县一名、市一名、省两名 */
    private void walkToFinal(Long id) {
        walkToProvince(id);
        assertNotNull(billService.approve(id, "省甲", "省审定一同核"));
        assertNotNull(billService.approve(id, "省乙", "省审定二同核"));
    }

    // ---------------- 并署办法：每道落几名由本道自己定 ----------------

    @Test
    void 任一人即过_头一名落下就算过() {
        TAdrReviewBill bill = seedBill("Q-OR");
        TAdrReviewBill after = billService.approve(bill.getId(), "县某", "材料齐");
        assertEquals(1, after.getNodeNo().intValue(), "县初核任一人即过，头一名落下就进到市复核");
        assertEquals(0, after.getSignCount().intValue(), "新到一口，本口还没落名");
        assertEquals(0, after.getStatus().intValue(), "在核");
        assertEquals(1, after.getNeedCount().intValue(), "市复核也是任一人即过");
        List<TAdrReviewSign> traces = allTraces(bill.getId());
        assertEquals(1, traces.size());
        assertEquals(0, traces.get(0).getNodeNo().intValue(), "这一笔落在县初核");
        assertEquals(1, traces.get(0).getBatchNo().intValue(), "头一趟起签为头一批");
        assertEquals("县某", traces.get(0).getSigner());
        assertEquals("sign", traces.get(0).getAction());
    }

    @Test
    void 两名点齐_落一名照旧候名_落满两名才算过() {
        TAdrReviewBill bill = seedBill("Q-AND");
        walkToProvince(bill.getId());
        assertEquals(2, billService.selectTAdrReviewBillById(bill.getId()).getNodeNo().intValue());
        assertEquals(2, billService.selectTAdrReviewBillById(bill.getId()).getNeedCount().intValue(),
                "省审定那一道定成两名点齐");

        TAdrReviewBill one = billService.approve(bill.getId(), "省甲", "头一名");
        assertEquals(2, one.getNodeNo().intValue(), "落了一名还停在省审定");
        assertEquals(1, one.getSignCount().intValue(), "本口已落一名");
        assertEquals(0, one.getStatus().intValue(), "照旧候名，不算过");

        TAdrReviewBill two = billService.approve(bill.getId(), "省乙", "第二名");
        assertEquals(2, two.getSignCount().intValue(), "落满两名");
        assertEquals(1, two.getStatus().intValue(), "全件核讫");
        assertEquals(4, allTraces(bill.getId()).size(), "县一、市一、省二，一笔一行");
    }

    // ---------------- 已落名数由签迹点算，栏里敲的不算 ----------------

    @Test
    void 已落名数由签迹点算_屏上栏里敲的不算() {
        TAdrReviewBill bill = seedBill("Q-COUNT");
        // 栏上被人写花：已落名敲成99、道次写成省审定、情形写成已核讫
        TAdrReviewBill tampered = stored(bill.getId());
        tampered.setSignCount(99);
        tampered.setNodeNo(2);
        tampered.setStatus(1);
        reviewBillMapper.updateById(tampered);

        TAdrReviewBill view = billService.selectTAdrReviewBillById(bill.getId());
        assertEquals(0, view.getNodeNo().intValue(), "道次以点算为准：还在县初核");
        assertEquals(0, view.getSignCount().intValue(), "本口一名未落，99不算");
        assertEquals(0, view.getStatus().intValue(), "在核，屏上写的已核不算");

        walkToProvince(bill.getId());
        TAdrReviewBill one = billService.approve(bill.getId(), "省甲", "头一名");
        assertEquals(1, one.getSignCount().intValue(), "顺着签迹点出一名，不是99上添出来的");
        assertEquals(1, stored(bill.getId()).getSignCount().intValue(), "栏上那份也被点算出来的数盖回");
    }

    // ---------------- 头一条硬杠杠：不许抢步 ----------------

    @Test
    void 抢步视同没落_县初核空着市复核落不进_省审定跨道落不进() {
        TAdrReviewBill bill = seedBill("Q-SKIP");
        assertNull(billService.approve(bill.getId(), 1, "市某", "替县里落名"),
                "县初核那一道还空着，市复核替它落名，这一笔视同没落");
        assertNull(billService.approve(bill.getId(), 2, "省某", "跨道落章"),
                "省审定跨过市复核直接落章，视同没落");
        assertEquals(0, allTraces(bill.getId()).size(), "一笔也没落进去");
        assertEquals(0, billService.selectTAdrReviewBillById(bill.getId()).getNodeNo().intValue());

        assertNotNull(billService.approve(bill.getId(), 0, "县某", "对得上道才落得进"));
        assertNull(billService.approve(bill.getId(), 0, "县某", "回头再补一笔"),
                "单已到市复核，回头往县初核补也算抢步");
        assertNull(billService.approve(bill.getId(), 2, "省某", "还是跨道"));
        assertEquals(1, allTraces(bill.getId()).size(), "只有对得上道的那一笔在账上");
    }

    @Test
    void 换一种签法_同名重载指明道次_对得上才落得进() {
        TAdrReviewBill bill = seedBill("Q-OVERLOAD");
        assertNotNull(billService.approve(bill.getId(), Integer.valueOf(0), "县某", "县初核讫"));
        assertNotNull(billService.approve(bill.getId(), Integer.valueOf(1), "市某", "市复核讫"));
        assertNotNull(billService.approve(bill.getId(), Integer.valueOf(2), "省甲", "省头一名"));
        TAdrReviewBill done = billService.approve(bill.getId(), Integer.valueOf(2), "省乙", "省第二名");
        assertEquals(1, done.getStatus().intValue());
        // 打回那一对也有指明道次的重载
        TAdrReviewBill other = seedBill("Q-OVERLOAD-2");
        assertNull(billService.reject(other.getId(), 1, "市某", "抢道打回"));
        TAdrReviewBill vetoed = billService.reject(other.getId(), Integer.valueOf(0), "县某", "县里打回");
        assertEquals(2, vetoed.getStatus().intValue(), "已打回");
    }

    // ---------------- 打回：钉死、整批退档、退回县初核重新起签 ----------------

    @Test
    void 省审定打回_整批退档_退回县初核重新起签_两批各归各堆() {
        TAdrReviewBill bill = seedBill("Q-VETO");
        walkToProvince(bill.getId());
        billService.approve(bill.getId(), "省甲", "省里看了一轮");

        TAdrReviewBill vetoed = billService.reject(bill.getId(), "省丙", "表述不合规矩，打回");
        assertEquals(0, vetoed.getNodeNo().intValue(), "退回县初核那一道");
        assertEquals(2, vetoed.getStatus().intValue(), "已打回");
        assertEquals(0, vetoed.getSignCount().intValue(), "新批空着手起");
        assertEquals(1, vetoed.getNeedCount().intValue());

        List<TAdrReviewSign> traces = allTraces(bill.getId());
        assertEquals(4, traces.size(), "县一、市一、省一，连打回那一笔共四笔");
        for (TAdrReviewSign s : traces) {
            assertEquals(1, s.getBatchNo().intValue(), "都是头一批");
            assertEquals(1, s.getPile().intValue(), "整批退档，一笔不落");
            assertNotNull(s.getArchiveTime(), "退档那一刻落账");
        }
        TAdrReviewSign veto = traces.get(3);
        assertEquals("reject", veto.getAction());
        assertEquals("省丙", veto.getSigner(), "谁写下的打回，谁落名");
        assertEquals(2, veto.getNodeNo().intValue(), "打回钉在省审定那一道");
        assertTrue(liveTraces(bill.getId()).isEmpty(), "屏上不再摆退档那一批");

        // 重新起签：新补的另起一批，与退档那批各归各堆
        TAdrReviewBill restart = billService.approve(bill.getId(), "县二", "县里重新起签");
        assertEquals(0, restart.getStatus().intValue(), "重新在核");
        assertEquals(1, restart.getNodeNo().intValue());
        List<TAdrReviewSign> live = liveTraces(bill.getId());
        assertEquals(1, live.size(), "屏上只摆新补那一批");
        assertEquals(2, live.get(0).getBatchNo().intValue());
        assertEquals("县二", live.get(0).getSigner());
        assertEquals(5, allTraces(bill.getId()).size(), "退档那批一句不毁，还在账上");
        assertEquals("省甲", allTraces(bill.getId()).get(2).getSigner(), "已落的签一个字没改");
    }

    @Test
    void 打回钉死_不往上加签_不改已落的签_旁道接不下去() {
        TAdrReviewBill bill = seedBill("Q-PIN");
        walkToProvince(bill.getId());
        billService.reject(bill.getId(), "省丙", "打回");

        assertNull(billService.approve(bill.getId(), 2, "省丁", "想替省里接着落"),
                "钉死在该道，旁道没有替他接下去的份");
        assertNull(billService.approve(bill.getId(), 1, "市某", "想从市里接着走"),
                "不往上加签");
        assertNull(billService.reject(bill.getId(), "省丁", "再打一回"),
                "已打回还没重新起签，无可再打");
        List<TAdrReviewSign> traces = allTraces(bill.getId());
        assertEquals(3, traces.size(), "县一、市一、打回一笔，一笔没多");
        assertEquals("县某", traces.get(0).getSigner(), "已落的签还是原来那个人");
        assertEquals("市某", traces.get(1).getSigner());
    }

    @Test
    void 已核讫锁死_签不得也打回不得() {
        TAdrReviewBill bill = seedBill("Q-FINAL");
        walkToFinal(bill.getId());
        assertNull(billService.approve(bill.getId(), "省丙", "想再添一名"));
        assertNull(billService.reject(bill.getId(), "省丙", "想打回"));
        assertEquals(4, allTraces(bill.getId()).size());
        assertEquals(1, billService.selectTAdrReviewBillById(bill.getId()).getStatus().intValue());
    }

    // ---------------- 两个姓名各归各 ----------------

    @Test
    void 两个姓名各归各_立单人跟单走到底_办一次续一名() {
        TAdrReviewBill bill = seedBill("Q-NAME");
        walkToFinal(bill.getId());
        assertEquals("立单人", stored(bill.getId()).getCreateBy(),
                "立单经手人一旦落定跟单走到底，往后再录多少回也轮不着他改人");
        List<TAdrReviewSign> traces = allTraces(bill.getId());
        assertEquals("县某", traces.get(0).getSigner());
        assertEquals("市某", traces.get(1).getSigner());
        assertEquals("省甲", traces.get(2).getSigner());
        assertEquals("省乙", traces.get(3).getSigner(),
                "各笔各记各的人，后一道的记录人没覆进前一道的格");
    }

    @Test
    void 空落名人不许落笔() {
        TAdrReviewBill bill = seedBill("Q-BLANK");
        assertNull(billService.approve(bill.getId(), "  ", "名字空着"));
        assertNull(billService.approve(bill.getId(), null, "名字没有"));
        assertNull(billService.reject(bill.getId(), "", "名字空着"));
        assertEquals(0, allTraces(bill.getId()).size(), "查不到实际署名的人，一笔落不下");
    }

    // ---------------- 末了一桩：倒读清点 ----------------

    @Test
    void 定稿那版_从省审定一路数回县初核_笔笔对得拢() {
        TAdrReviewBill bill = seedBill("Q-AUDIT");
        walkToFinal(bill.getId());
        SignAuditVo vo = billService.auditSigns(bill.getId());
        assertTrue(vo.isPassed(), vo.getMessage());
        assertEquals(4, vo.getTotalCount());
        assertEquals(1, vo.getCountyCount());
        assertEquals(1, vo.getCityCount());
        assertEquals(2, vo.getProvinceCount());
    }

    @Test
    void 倒读_屏上写已核档里只一名_判对不拢() {
        TAdrReviewBill bill = seedBill("Q-AUDIT-SHORT");
        walkToProvince(bill.getId());
        billService.approve(bill.getId(), "省甲", "只落了一名");
        // 去年那桩：屏上写"已核"，档里只有一名
        TAdrReviewBill tampered = stored(bill.getId());
        tampered.setStatus(1);
        tampered.setSignCount(2);
        reviewBillMapper.updateById(tampered);

        SignAuditVo vo = billService.auditSigns(bill.getId());
        assertFalse(vo.isPassed(), "屏上写已核不作数，档里点出来才算");
        assertEquals(1, vo.getProvinceCount(), "省审定那一道只点出一名");
    }

    @Test
    void 倒读_栏上那份被人写花_与签迹对不拢() {
        TAdrReviewBill bill = seedBill("Q-AUDIT-TAMPER");
        walkToFinal(bill.getId());
        // 定稿是真定稿，栏上那份却被人写花
        TAdrReviewBill tampered = stored(bill.getId());
        tampered.setSignCount(1);
        reviewBillMapper.updateById(tampered);

        SignAuditVo vo = billService.auditSigns(bill.getId());
        assertFalse(vo.isPassed());
        assertTrue(vo.getMessage().contains("对不拢"), vo.getMessage());
    }

    @Test
    void 倒读_混进不该在的笔_判对不拢() {
        TAdrReviewBill bill = seedBill("Q-AUDIT-EXTRA");
        walkToFinal(bill.getId());
        // 事后由人手往现行批里塞一笔
        TAdrReviewSign extra = new TAdrReviewSign();
        extra.setBillId(bill.getId());
        extra.setBillNo("Q-AUDIT-EXTRA");
        extra.setNodeNo(0);
        extra.setBatchNo(1);
        extra.setAction("sign");
        extra.setSigner("手补的人");
        extra.setPile(0);
        extra.setDelFlag(0);
        reviewSignMapper.insert(extra);

        SignAuditVo vo = billService.auditSigns(bill.getId());
        assertFalse(vo.isPassed());
        assertEquals(2, vo.getCountyCount(), "县初核多出一笔，单拿这一道就经不起复核");
    }

    @Test
    void 倒读_落笔先后与道次对不上_判对不拢() {
        TAdrReviewBill bill = seedBill("Q-AUDIT-ORDER");
        walkToFinal(bill.getId());
        // 人手把两笔的道次改花：名数还在，先后对不上
        List<TAdrReviewSign> traces = allTraces(bill.getId());
        TAdrReviewSign county = traces.get(0);
        TAdrReviewSign city = traces.get(1);
        county.setNodeNo(1);
        city.setNodeNo(0);
        reviewSignMapper.updateById(county);
        reviewSignMapper.updateById(city);

        SignAuditVo vo = billService.auditSigns(bill.getId());
        assertFalse(vo.isPassed());
        assertTrue(vo.getMessage().contains("对不上"), vo.getMessage());
    }

    @Test
    void 重签那一趟走完_倒读只数新补那批_退档的不算进定稿那版() {
        TAdrReviewBill bill = seedBill("Q-AUDIT-REDO");
        walkToProvince(bill.getId());
        billService.approve(bill.getId(), "省甲", "省里看了一轮");
        billService.reject(bill.getId(), "省丙", "表述不合规矩，打回");
        // 重新起签，再一道一道往上走
        assertNotNull(billService.approve(bill.getId(), "县二", "县重签"));
        assertNotNull(billService.approve(bill.getId(), "市二", "市重签"));
        assertNotNull(billService.approve(bill.getId(), "省丁", "省重签一"));
        assertNotNull(billService.approve(bill.getId(), "省戊", "省重签二"));
        assertEquals(1, billService.selectTAdrReviewBillById(bill.getId()).getStatus().intValue());

        SignAuditVo vo = billService.auditSigns(bill.getId());
        assertTrue(vo.isPassed(), vo.getMessage());
        assertEquals(4, vo.getTotalCount(), "定稿那一版只有新补那批四笔，退档那批不摆进来");
        assertEquals(8, allTraces(bill.getId()).size(), "两批都在账上，一句不毁");
    }

    // ---------------- 第二件当场点名，不并进头一张单 ----------------

    @Test
    void 第二件要复核的当场点名_另立一单_不并进头一张单() {
        TAdrReviewBill first = seedBill("Q-ONE");
        TAdrReviewBill second = seedBill("Q-TWO");
        walkToProvince(first.getId());
        assertEquals(0, allTraces(second.getId()).size(), "头一张走头一张的，第二件不并进来");
        TAdrReviewBill view = billService.selectTAdrReviewBillById(second.getId());
        assertEquals(0, view.getNodeNo().intValue(), "第二件还停在县初核");
        assertEquals(0, view.getSignCount().intValue());
        assertNotNull(billService.approve(second.getId(), "县丙", "第二件自己走"));
        assertEquals(2, allTraces(first.getId()).size(), "各走各的，互不沾");
        assertEquals(1, allTraces(second.getId()).size());
    }

    // ---------------- 退回上一环节这一着不再使得 ----------------

    @Test
    void 退回只走打回_没有往回退一格这一着() {
        TAdrReviewBill bill = seedBill("Q-ROLLBACK");
        assertNull(billService.rollback(bill.getId(), "还没走就想退"));
        walkToProvince(bill.getId());
        assertNull(billService.rollback(bill.getId(), "想退一格"), "已落的签抽不回来");
        assertEquals(2, allTraces(bill.getId()).size(), "一笔没动");
        assertEquals(2, billService.selectTAdrReviewBillById(bill.getId()).getNodeNo().intValue());
    }
}
