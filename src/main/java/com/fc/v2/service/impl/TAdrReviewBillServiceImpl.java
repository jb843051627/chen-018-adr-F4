package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TAdrReviewBillMapper;
import com.fc.v2.mapper.auto.TAdrReviewSignMapper;
import com.fc.v2.model.auto.TAdrReviewBill;
import com.fc.v2.model.auto.TAdrReviewSign;
import com.fc.v2.model.custom.adr.SignAuditVo;
import com.fc.v2.service.ITAdrReviewBillService;

/**
 * 评价结论逐级复核签认单 Service业务层处理（approval-chain 形状：三道口逐级签批）。
 *
 * 去年夏天那桩返工立的规：县里报上来的件，市里一位同志看材料齐了就顺手把结论定了，
 * 省里复核时发觉该两名点齐的那一口只落了一个名——屏上写"已核"，档里只有一名。
 * 追下来不是谁的错，是落名这件事没人点算。今年起：
 *
 * 一、三道口县初核(0)、市复核(1)、省审定(2)一道一道往上走，每道落几名由本道自己定
 *    （县、市任一人即过；省审定两名点齐），并署办法不给人随口改——库里栏上写花了也不算，
 *    系统只认本道自己的规矩。
 * 二、本口已落名数不由人往栏里敲，顺着签迹（t_adr_review_sign）一笔一笔点出来才算；
 *    界面摆着的数、经办人自己记住的数，都当不得依据。
 * 三、不许抢步：指明的道次与单当前所在道对不上，这一笔视同没落。哪一道写下打回，
 *    这单就钉死在该道——不往上加签、不回头改已落的签、旁道没有替他接下去的份；
 *    此前各道攒下的署名整批退档，这单退回县初核重新起签，退档那批与新补那批各归各堆。
 * 四、落名栏里两个姓名各归各：立单经手人（create_by）一旦落定跟单走到底，谁也改不了；
 *    跟着这一趟走的记录人落在签迹上，办一次续一名，只进不出，不许把后一道的覆进前一道的格。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TAdrReviewBillServiceImpl implements ITAdrReviewBillService {

    /** 三道口：0县初核 1市复核 2省审定 */
    private static final int NODE_COUNTY = 0;
    private static final int NODE_CITY = 1;
    private static final int NODE_PROVINCE = 2;
    private static final int NODE_COUNT = 3;
    private static final String[] NODE_NAMES = {"县初核", "市复核", "省审定"};

    /** 同口并署办法：0任一人即过 1两名点齐 */
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;

    /** 报批情形：0在核 1已核讫 2已打回 */
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    /** 落笔情形 */
    private static final String ACT_SIGN = "sign";
    private static final String ACT_REJECT = "reject";

    /** 档层：0现行 1退档（退档那批沉下去，不与新补的同列） */
    private static final int PILE_CURRENT = 0;
    private static final int PILE_ARCHIVED = 1;

    private static final int DEL_NORMAL = 0;

    @javax.annotation.Resource
    private TAdrReviewBillMapper adrReviewBillMapper;
    @javax.annotation.Resource
    private TAdrReviewSignMapper adrReviewSignMapper;

    @Override
    public TAdrReviewBill selectTAdrReviewBillById(Long id) {
        if (id == null) {
            return null;
        }
        TAdrReviewBill bill = this.adrReviewBillMapper.selectById(id);
        if (bill == null) {
            return null;
        }
        // 回来的这份，道次、已落名数、当下算哪一步，一律顺着签迹当场点算；
        // 库里栏上那份原样留着不动——倒读清点还要拿它与签迹对账，先抹平就没得对了
        return viewOf(bill, derive(bill.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TAdrReviewBill approve(Long id, String approver, String comment) {
        return doApprove(id, null, approver, comment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TAdrReviewBill approve(Long id, Integer nodeNo, String approver, String comment) {
        return doApprove(id, nodeNo, approver, comment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TAdrReviewBill reject(Long id, String approver, String comment) {
        return doReject(id, null, approver, comment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TAdrReviewBill reject(Long id, Integer nodeNo, String approver, String comment) {
        return doReject(id, nodeNo, approver, comment);
    }

    @Override
    public TAdrReviewBill rollback(Long id, String comment) {
        // 退回只走打回那一对（整批退档、退回县初核重新起签）；没有"往回退一格"这一着——
        // 已落的签一句改不得，也抽不回来。签名不动，一律被拒。
        return null;
    }

    @Override
    public SignAuditVo auditSigns(Long id) {
        if (id == null) {
            return new SignAuditVo(false, "查无此单", 0, 0, 0, 0);
        }
        TAdrReviewBill bill = this.adrReviewBillMapper.selectById(id);
        if (bill == null) {
            return new SignAuditVo(false, "查无此单", 0, 0, 0, 0);
        }
        ChainState st = derive(bill.getId());
        int county = st.signedPerNode[NODE_COUNTY];
        int city = st.signedPerNode[NODE_CITY];
        int province = st.signedPerNode[NODE_PROVINCE];
        int total = county + city + province;
        if (st.status != STATUS_PASS) {
            return new SignAuditVo(false,
                    "这一件还没定稿（当下" + statusName(st.status) + "），谈不上定稿那一版", total, county, city, province);
        }
        // 单拿出任意一道也经得起复核：各道点出来的名数必须正好是该道口应落的数，
        // 少了是没走够，多了是混进了不该在的笔
        for (int n = 0; n < NODE_COUNT; n++) {
            if (st.signedPerNode[n] != quorumOf(n)) {
                return new SignAuditVo(false,
                        NODE_NAMES[n] + "那一道点出" + st.signedPerNode[n] + "名，与该口应落"
                                + quorumOf(n) + "名对不拢", total, county, city, province);
            }
        }
        // 从省审定一路数回县初核：落笔先后（主键升序）必须与道次对得上，
        // 对不上就是抢步落进去的、或事后由人手补进去的
        int prev = -1;
        for (TAdrReviewSign s : st.live) {
            if (!ACT_SIGN.equals(s.getAction())) {
                return new SignAuditVo(false, "定稿那一版里混着一笔打回，这一版不算走成", total, county, city, province);
            }
            if (s.getNodeNo() == null || s.getNodeNo() < prev) {
                return new SignAuditVo(false, "落笔先后与道次对不上，这一版不算走成", total, county, city, province);
            }
            prev = s.getNodeNo();
            if (s.getSigner() == null || s.getSigner().trim().isEmpty()) {
                return new SignAuditVo(false, "查不到实际署名的人，这一版不算走成", total, county, city, province);
            }
        }
        // 点出来的总数必须与当天屏上那一份对得拢：库里栏上那份（屏上摆的）与签迹点出来的对
        if (!Integer.valueOf(STATUS_PASS).equals(bill.getStatus())
                || !Integer.valueOf(NODE_PROVINCE).equals(bill.getNodeNo())
                || !Integer.valueOf(province).equals(bill.getSignCount())) {
            return new SignAuditVo(false,
                    "屏上那一份与签认档里点出来的对不拢（栏上情形" + bill.getStatus() + "、道次"
                            + bill.getNodeNo() + "、已落名" + bill.getSignCount() + "）", total, county, city, province);
        }
        return new SignAuditVo(true, "从省审定一路数回县初核，笔笔对得拢，这一件定稿经得起清点", total, county, city, province);
    }

    // ----------------------------------------------------------------------

    /**
     * 签认一票：落在当前所在那一道。过口自然进到下一道；省审定落满，全件核讫。
     * 被拒（查无此单、落名人空着、已核讫、指明道次对不上）返回 null。
     */
    private TAdrReviewBill doApprove(Long id, Integer nodeNo, String approver, String comment) {
        if (id == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        TAdrReviewBill bill = this.adrReviewBillMapper.selectById(id);
        if (bill == null) {
            return null;
        }
        ChainState st = derive(bill.getId());
        if (st.status == STATUS_PASS) {
            return null; // 已核讫的单锁死，没有再添一笔的份
        }
        if (nodeNo != null && nodeNo.intValue() != st.nodeNo) {
            return null; // 不许抢步：指明的道次与当前所在道对不上，这一笔视同没落
        }
        // 落笔只进不出：一笔签认落一行签迹，这一笔实际落名的人记在这一行上
        TAdrReviewSign sign = newTrace(bill, st, ACT_SIGN, approver, comment);
        this.adrReviewSignMapper.insert(sign);
        // 落完这一笔，顺着签迹重新点算，把点出来的交回——签没签成只看它交回什么
        ChainState after = derive(bill.getId());
        persist(bill.getId(), after);
        return viewOf(bill, after);
    }

    /**
     * 打回：钉死在该道，此前各道攒下的署名整批退档，这单退回县初核重新起签。
     * 被拒（查无此单、落名人空着、没在核、指明道次对不上）返回 null。
     */
    private TAdrReviewBill doReject(Long id, Integer nodeNo, String approver, String comment) {
        if (id == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        TAdrReviewBill bill = this.adrReviewBillMapper.selectById(id);
        if (bill == null) {
            return null;
        }
        ChainState st = derive(bill.getId());
        if (st.status != STATUS_RUNNING) {
            return null; // 已核讫锁死；已打回还没重新起签的，无可再打
        }
        if (nodeNo != null && nodeNo.intValue() != st.nodeNo) {
            return null; // 抢步打回，视同没落
        }
        // 打回这一笔同样落签迹：哪一道、谁写下的打回，一笔一笔有据
        TAdrReviewSign veto = newTrace(bill, st, ACT_REJECT, approver, comment);
        this.adrReviewSignMapper.insert(veto);
        // 整批退档：这一批（连打回这一笔）整批沉下，与日后新补的那批各归各堆；
        // 只动档层与退档时刻，已落的签一个字不改
        this.adrReviewSignMapper.update(null, new UpdateWrapper<TAdrReviewSign>()
                .eq("bill_id", bill.getId())
                .eq("batch_no", st.batchNo)
                .eq("pile", PILE_CURRENT)
                .set("pile", PILE_ARCHIVED)
                .set("archive_time", new Date()));
        // 退回县初核那一道重新起签：新批从县初核空着手起
        ChainState after = derive(bill.getId());
        persist(bill.getId(), after);
        return viewOf(bill, after);
    }

    /** 落一行签迹：当前批、当前道、这一笔实际落名的人 */
    private TAdrReviewSign newTrace(TAdrReviewBill bill, ChainState st, String action, String signer, String note) {
        TAdrReviewSign s = new TAdrReviewSign();
        s.setBillId(bill.getId());
        s.setBillNo(bill.getBillNo());
        s.setNodeNo(Integer.valueOf(st.nodeNo));
        s.setBatchNo(Integer.valueOf(st.batchNo));
        s.setAction(action);
        s.setSigner(signer);
        s.setNote(note);
        s.setPile(Integer.valueOf(PILE_CURRENT));
        s.setDelFlag(Integer.valueOf(DEL_NORMAL));
        s.setCreateTime(new Date());
        return s;
    }

    /** 把点算出来的数落回单上（栏上那份只供屏上摆；立单经手人那一栏永不在这里出现） */
    private void persist(Long billId, ChainState st) {
        this.adrReviewBillMapper.update(null, new UpdateWrapper<TAdrReviewBill>()
                .eq("id", billId)
                .set("node_no", st.nodeNo)
                .set("sign_mode", modeOf(st.nodeNo))
                .set("need_count", quorumOf(st.nodeNo))
                .set("sign_count", st.signedHere)
                .set("status", st.status));
    }

    /** 屏上那份视图：另拷一份再叠上点算出来的数，不动库里捞出来那份 */
    private TAdrReviewBill viewOf(TAdrReviewBill bill, ChainState st) {
        TAdrReviewBill v = copyOf(bill);
        v.setNodeNo(Integer.valueOf(st.nodeNo));
        v.setSignMode(Integer.valueOf(modeOf(st.nodeNo)));
        v.setNeedCount(Integer.valueOf(quorumOf(st.nodeNo)));
        v.setSignCount(Integer.valueOf(st.signedHere));
        v.setStatus(Integer.valueOf(st.status));
        return v;
    }

    private static TAdrReviewBill copyOf(TAdrReviewBill src) {
        TAdrReviewBill dst = new TAdrReviewBill();
        dst.setId(src.getId());
        dst.setBillNo(src.getBillNo());
        dst.setNodeNo(src.getNodeNo());
        dst.setSignMode(src.getSignMode());
        dst.setNeedCount(src.getNeedCount());
        dst.setSignCount(src.getSignCount());
        dst.setStatus(src.getStatus());
        dst.setDelFlag(src.getDelFlag());
        dst.setCreateBy(src.getCreateBy());
        dst.setCreateTime(src.getCreateTime());
        dst.setUpdateBy(src.getUpdateBy());
        dst.setUpdateTime(src.getUpdateTime());
        dst.setRemark(src.getRemark());
        return dst;
    }

    /**
     * 顺着签迹点算这一单当下的情形：当前批号、当前所在道次、本口已落名数、当下算哪一步。
     * 当前批＝落库最晚的那一批；那一批若已整批退档，当前批就是空着手的下一批。
     */
    private ChainState derive(Long billId) {
        List<TAdrReviewSign> all = this.adrReviewSignMapper.selectList(new QueryWrapper<TAdrReviewSign>()
                .eq("bill_id", billId)
                .eq("del_flag", DEL_NORMAL)
                .orderByAsc("id"));
        ChainState st = new ChainState();
        st.all = all;
        int maxBatch = 0;
        for (TAdrReviewSign s : all) {
            if (s.getBatchNo() != null && s.getBatchNo() > maxBatch) {
                maxBatch = s.getBatchNo();
            }
        }
        int batch = maxBatch == 0 ? 1 : maxBatch;
        List<TAdrReviewSign> live = new ArrayList<>();
        for (TAdrReviewSign s : all) {
            if (s.getBatchNo() != null && s.getBatchNo() == batch
                    && Integer.valueOf(PILE_CURRENT).equals(s.getPile())) {
                live.add(s);
            }
        }
        if (maxBatch > 0 && live.isEmpty()) {
            // 最晚那批已整批退档：重新起签的是下一批，空着手
            batch = maxBatch + 1;
        }
        st.batchNo = batch;
        st.live = live;
        for (TAdrReviewSign s : live) {
            if (ACT_SIGN.equals(s.getAction()) && s.getNodeNo() != null
                    && s.getNodeNo() >= 0 && s.getNodeNo() < NODE_COUNT) {
                st.signedPerNode[s.getNodeNo()]++;
            }
        }
        // 当前所在道次＝头一道还没点齐名的口；三道都点齐，停在省审定
        int node = NODE_PROVINCE;
        for (int n = 0; n < NODE_COUNT; n++) {
            if (st.signedPerNode[n] < quorumOf(n)) {
                node = n;
                break;
            }
        }
        st.nodeNo = node;
        st.signedHere = st.signedPerNode[node];
        // 当下算哪一步：省审定点齐＝已核讫；最新一笔是打回且新批还没起签＝已打回；其余在核
        if (st.signedPerNode[NODE_PROVINCE] >= quorumOf(NODE_PROVINCE)) {
            st.status = STATUS_PASS;
        } else if (live.isEmpty() && !all.isEmpty()
                && ACT_REJECT.equals(all.get(all.size() - 1).getAction())) {
            st.status = STATUS_VETO;
        } else {
            st.status = STATUS_RUNNING;
        }
        return st;
    }

    /** 每道落几名由本道自己定：县初核、市复核任一人即过；省审定两名点齐 */
    private static int quorumOf(int node) {
        return node == NODE_PROVINCE ? 2 : 1;
    }

    /** 本口并署办法跟着应落名数走，不给人随口改 */
    private static int modeOf(int node) {
        return quorumOf(node) >= 2 ? MODE_AND : MODE_OR;
    }

    private static String statusName(int status) {
        switch (status) {
            case STATUS_PASS:
                return "已核讫";
            case STATUS_VETO:
                return "已打回";
            default:
                return "在核";
        }
    }

    /** 一单当下的点算结果 */
    private static class ChainState {
        /** 当前批号（头一趟起签为1，打回退档后重新起签另起一批） */
        int batchNo;
        /** 当前所在道次 0县初核 1市复核 2省审定 */
        int nodeNo;
        /** 本口已落名数（当前批、当前道的签认笔数） */
        int signedHere;
        /** 报批情形 0在核 1已核讫 2已打回 */
        int status;
        /** 当前批各道签认笔数 */
        final int[] signedPerNode = new int[NODE_COUNT];
        /** 当前批在册签迹（按落库先后） */
        List<TAdrReviewSign> live = new ArrayList<>();
        /** 全部在册签迹（按落库先后） */
        List<TAdrReviewSign> all = new ArrayList<>();
    }
}
