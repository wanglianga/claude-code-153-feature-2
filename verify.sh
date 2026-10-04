#!/usr/bin/env bash
# 端到端验证脚本：覆盖登记→上报→审核→派单→称重→处置→计费→投诉→追责全链路
# 前置：应用已启动（默认 http://localhost:8080），数据库为全新状态（演示数据已初始化）
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
PASS=0; FAIL=0

check() { # $1=描述 $2=实际内容 $3=期望包含的子串
  if [[ "$2" == *"$3"* ]]; then
    PASS=$((PASS+1)); echo "  ✔ $1"
  else
    FAIL=$((FAIL+1)); echo "  ✘ $1"; echo "    期望包含: $3"; echo "    实际: $(echo "$2" | head -c 400)"
  fi
}

login() { curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | jq -r '.data.token'; }

J() { jq -r "$1" 2>/dev/null; }

TODAY=$(date +%F)
YESTERDAY=$(date -d "yesterday" +%F)
TOMORROW=$(date -d "tomorrow" +%F)
NEXT_MONDAY=$(date -d "next monday" +%F)
NOW=$(date +%FT%T)
PLUS2H=$(date -d "+2 hours" +%FT%T)
PERIOD=$(date +%Y-%m)

echo "== 0. 认证与鉴权 =="
UNAUTH=$(curl -s "$BASE/api/registrations")
check "未登录访问返回 401" "$UNAUTH" "未登录"
OWNER_T=$(login owner1 123456);  OWNER2_T=$(login owner2 123456)
WORKER_T=$(login worker1 123456); PROP_T=$(login property1 123456)
COLL_T=$(login collector1 123456); FIN_T=$(login finance1 123456)
check "六个角色登录获取令牌" "$([ -n "$OWNER_T" ] && [ -n "$FIN_T" ] && echo ok)" "ok"
ME=$(curl -s "$BASE/api/auth/me" -H "Authorization: Bearer $OWNER_T")
check "当前用户为张三(OWNER)" "$ME" '"name":"张三"'

AUTH_PROP="Authorization: Bearer $PROP_T"; AUTH_COLL="Authorization: Bearer $COLL_T"
AUTH_FIN="Authorization: Bearer $FIN_T"; AUTH_OWNER="Authorization: Bearer $OWNER_T"
AUTH_WORKER="Authorization: Bearer $WORKER_T"; CT="Content-Type: application/json"

echo "== 1. 主流程：上报→审核→派单→称重→处置→计费 =="
R1=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":1,\"reportDate\":\"$TODAY\",\"bagCount\":10,\"extraBagCount\":2,\"stackingLocation\":\"1号楼垃圾点旁\",\"wastePointId\":1,\"containsWood\":true,\"containsGlass\":true,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORT1=$(echo "$R1" | J '.data.id')
check "施工队每日上报(10袋含2加袋)" "$R1" '"status":"PENDING"'

A1=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT1,\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"AM\",\"comment\":\"同意清运\"}")
APPT1=$(echo "$A1" | J '.data.id')
check "物业审核通过生成预约" "$A1" '"status":"APPROVED"'

D1=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT1,\"vehicleId\":1,\"plannedArrival\":\"$PLUS2H\",\"routeInfo\":\"东门进→1号楼→西门出\"}")
DISP1=$(echo "$D1" | J '.data.id')
check "清运公司派单(含门禁放行单号)" "$D1" '"gatePassNo":"GP'

ARR=$(curl -s -X POST "$BASE/api/dispatches/$DISP1/arrive" -H "$AUTH_COLL" -H "$CT" -d "{\"actualArrival\":\"$NOW\"}")
check "司机到场登记(准时)" "$ARR" '"lateFlag":false'

W1=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP1,\"emptyWeightKg\":5000,\"loadedWeightKg\":5250,\"entryTime\":\"$NOW\",\"exitTime\":\"$(date +%FT%T)\",\"photos\":\"http://img/1.jpg\",\"manifestNo\":\"MF-TEST-001\"}")
WEIGH1=$(echo "$W1" | J '.data.id')
check "进出场称重(净重250kg无异常)" "$W1" '"anomalyFlag":false'

RC=$(curl -s -X POST "$BASE/api/disposal-receipts" -H "$AUTH_COLL" -H "$CT" -d "{\"weighRecordId\":$WEIGH1,\"disposalSite\":\"城东建筑垃圾处置场\",\"receivedWeightKg\":250}")
check "处置场回执" "$RC" '"receiptNo":"RC'

sleep 1  # 等待计费事件处理
BILLS=$(curl -s "$BASE/api/bills?type=OWNER" -H "$AUTH_FIN")
check "业主账单=88元(8袋×8+2加袋×8×1.5)" "$BILLS" '"amount":88.00'
check "业主账单押金抵扣已结清" "$BILLS" '"status":"PAID"'
OWNER_BILL=$(echo "$BILLS" | J '[.data[] | select(.appointmentId=='"$APPT1"')][0].id')
CBILLS=$(curl -s "$BASE/api/bills?type=COLLECTOR" -H "$AUTH_FIN")
check "清运公司结算单=30元(0.25吨×120)" "$CBILLS" '"amount":30.00'
COLL_BILL=$(echo "$CBILLS" | J '[.data[] | select(.appointmentId=='"$APPT1"')][0].id')
PAY=$(curl -s -X POST "$BASE/api/bills/$COLL_BILL/pay" -H "$AUTH_FIN")
check "财务结清清运公司账单" "$PAY" '"status":"PAID"'

TRACE=$(curl -s "$BASE/api/trace/appointments/$APPT1" -H "$AUTH_PROP")
for kw in SUBMIT_REPORT REVIEW_APPROVE DISPATCH_CREATE WEIGH_IN_OUT DISPOSAL_RECEIPT BILL_GENERATE '"status":"SETTLED"' 张三 李工 王经理 刘师傅; do
  check "全链路追溯包含 $kw" "$TRACE" "$kw"
done
TCODE=$(echo "$TRACE" | J '.data.bagTrace.traceCode')
check "按追溯码查询" "$(curl -s "$BASE/api/trace/code/$TCODE" -H "$AUTH_PROP")" '"apptNo"'

echo "== 2. 容量规则：接近上限拒绝→临时围挡→通过 =="
REG2=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"2栋\",\"roomNumber\":\"502\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+20 days' +%F)\",\"wasteTypes\":\"瓷砖,混凝土\",\"frequency\":\"DAILY\",\"depositAmount\":500}" | J '.data.id')
check "业主2登记(押金500)" "$([ -n "$REG2" ] && [ "$REG2" != "null" ] && echo ok)" "ok"

R2=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG2,\"reportDate\":\"$TODAY\",\"bagCount\":10,\"extraBagCount\":0,\"stackingLocation\":\"2号楼垃圾点\",\"wastePointId\":2,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORT2=$(echo "$R2" | J '.data.id')

A2=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT2,\"approve\":true,\"apptDate\":\"$NEXT_MONDAY\",\"timeSlot\":\"AM\",\"comment\":\"\"}")
check "垃圾点接近上限→不简单通过" "$A2" '"status":"REJECTED_CAPACITY"'
check "提示错峰堆放" "$A2" "错峰"
check "提示临时围挡" "$A2" "围挡"

FENCE=$(curl -s -X POST "$BASE/api/waste-points/2/fence" -H "$AUTH_PROP" -H "$CT" -d '{"enable":true,"extraCapacity":20}')
check "物业安排临时围挡扩容+20" "$FENCE" '"tempFence":true'

A2B=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT2,\"approve\":true,\"apptDate\":\"$NEXT_MONDAY\",\"timeSlot\":\"AM\",\"comment\":\"已围挡\"}")
APPT2=$(echo "$A2B" | J '.data.id')
check "围挡扩容后审核通过" "$A2B" '"status":"APPROVED"'

D2X=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT2,\"vehicleId\":3,\"plannedArrival\":\"${NEXT_MONDAY}T10:00:00\"}")
check "尾号6车辆周一限行被拒" "$D2X" "限行"

D2=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT2,\"vehicleId\":1,\"plannedArrival\":\"${NEXT_MONDAY}T10:00:00\"}")
DISP2=$(echo "$D2" | J '.data.id')
check "更换车辆派单成功" "$D2" '"status":"DISPATCHED"'

W2=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP2,\"emptyWeightKg\":5000,\"loadedWeightKg\":5400,\"entryTime\":\"$NOW\",\"exitTime\":\"$(date +%FT%T)\",\"photos\":\"http://img/2.jpg\"}")
check "称重偏差60%→标记异常" "$W2" '"anomalyFlag":true'
sleep 1
CPLS=$(curl -s "$BASE/api/complaints" -H "$AUTH_PROP")
check "称重异常自动生成投诉" "$CPLS" '"type":"WEIGH_ANOMALY"'

echo "== 3. 违规：消防通道堆放+混入生活垃圾→押金扣款→施工队账单 =="
R3=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG2,\"reportDate\":\"$TOMORROW\",\"bagCount\":5,\"extraBagCount\":0,\"stackingLocation\":\"2号楼消防通道口\",\"wastePointId\":2,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":true}")
REPORT3=$(echo "$R3" | J '.data.id')
sleep 1
VIO=$(curl -s "$BASE/api/violations" -H "$AUTH_PROP")
check "消防通道堆放违规" "$VIO" '"type":"FIRE_LANE_STACKING"'
check "混入生活垃圾违规" "$VIO" '"type":"MIXED_HOUSEHOLD_WASTE"'
DEP=$(curl -s "$BASE/api/registrations/$REG2/deposit-log" -H "$AUTH_PROP")
check "押金扣款200(消防通道)" "$DEP" '"amount":200.00'
check "押金扣款100(混入生活垃圾)" "$DEP" '"amount":100.00'
REG2INFO=$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")
check "押金余额500-300-80(流程B清运费)=120" "$REG2INFO" '"depositBalance":120.00'
TEAM_BILLS=$(curl -s "$BASE/api/bills?type=CONSTRUCTION_TEAM" -H "$AUTH_FIN")
check "施工队罚款账单生成" "$TEAM_BILLS" '"billType":"CONSTRUCTION_TEAM"'
TEAM_BILL=$(echo "$TEAM_BILLS" | J '[.data[] | select(.amount==200)][0].id')
PAYT=$(curl -s -X POST "$BASE/api/bills/$TEAM_BILL/pay" -H "$AUTH_FIN")
check "施工队缴清罚款" "$PAYT" '"status":"PAID"'
REG2INFO2=$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")
check "罚款返还业主押金→320" "$REG2INFO2" '"depositBalance":320.00'

A3=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT3,\"approve\":true,\"apptDate\":\"$YESTERDAY\",\"timeSlot\":\"PM\",\"comment\":\"限期清理\"}")
check "违规上报仍可审核(昨日预约→超时未清)" "$A3" '"status":"APPROVED"'

echo "== 4. 费用争议：投诉→账单冻结→财务调整→押金回冲 =="
CD=$(curl -s -X POST "$BASE/api/complaints" -H "$AUTH_OWNER" -H "$CT" -d "{\"type\":\"FEE_DISPUTE\",\"billId\":$OWNER_BILL,\"description\":\"加袋费用有异议，要求复核\"}")
CPL1=$(echo "$CD" | J '.data.id')
check "业主发起费用争议投诉" "$CD" '"status":"OPEN"'
BILL_NOW=$(curl -s "$BASE/api/bills/$OWNER_BILL" -H "$AUTH_FIN")
check "账单自动转为争议状态" "$BILL_NOW" '"status":"DISPUTED"'
ADJ=$(curl -s -X POST "$BASE/api/bills/$OWNER_BILL/adjust" -H "$AUTH_FIN" -H "$CT" -d '{"newAmount":60,"reason":"复核后加袋按普通袋计费"}')
check "财务调整账单88→60" "$ADJ" '"amount":60.00'
REG1INFO=$(curl -s "$BASE/api/registrations/1" -H "$AUTH_PROP")
check "差额28元回冲押金(1912→1940)" "$REG1INFO" '"depositBalance":1940.00'
HDL=$(curl -s -X PUT "$BASE/api/complaints/$CPL1/handle" -H "$AUTH_PROP" -H "$CT" -d '{"action":"RESOLVED","resolution":"已复核调整账单并退还差额"}')
check "物业办结投诉" "$HDL" '"status":"RESOLVED"'

ODOR=$(curl -s -X POST "$BASE/api/complaints" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"type\":\"ODOR\",\"targetAppointmentId\":$APPT1,\"description\":\"垃圾点异味扰民\"}")
check "邻居投诉异味" "$ODOR" '"complaintNo":"CPL'

echo "== 5. 三端看板 =="
DP=$(curl -s "$BASE/api/dashboard/property" -H "$AUTH_PROP")
check "物业看板-楼栋统计" "$DP" '"building":"1栋"'
check "物业看板-超时未清" "$DP" '"overdueAppointments":[{'
check "物业看板-清运公司履约" "$DP" '"company":"绿洁清运"'
DC=$(curl -s "$BASE/api/dashboard/collector" -H "$AUTH_COLL")
check "清运看板-派单与车辆" "$DC" '"vehicles"'
DF=$(curl -s "$BASE/api/dashboard/finance?period=$PERIOD" -H "$AUTH_FIN")
check "财务看板-四类账单汇总" "$DF" '"PROPERTY_ADVANCE"'

echo "== 6. 称重差异复核：证据比对→合并清运拆分（袋数+材质+装车顺序，含人工调整） =="
# 两户同日预约：A_M 张四 3栋301 8袋瓷砖(点3)，A_X 张三 1栋902 6袋木板(点1)，同车合并清运整车600kg
REGM=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"3栋\",\"roomNumber\":\"301\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+15 days' +%F)\",\"wasteTypes\":\"瓷砖\",\"frequency\":\"DAILY\",\"depositAmount\":800}" | J '.data.id')
RM=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REGM,\"reportDate\":\"$TODAY\",\"bagCount\":8,\"extraBagCount\":0,\"stackingLocation\":\"3号楼临时堆放点旁\",\"wastePointId\":3,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORT_M=$(echo "$RM" | J '.data.id')
AM=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT_M,\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"PM\",\"comment\":\"同意\"}")
APPT_M=$(echo "$AM" | J '.data.id')
check "张四3栋301同日预约(8袋瓷砖)" "$AM" '"status":"APPROVED"'

REGX=$(curl -s -X POST "$BASE/api/registrations" -H "$AUTH_OWNER" -H "$CT" -d "{\"building\":\"1栋\",\"roomNumber\":\"902\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+15 days' +%F)\",\"wasteTypes\":\"木板\",\"frequency\":\"DAILY\",\"depositAmount\":800}" | J '.data.id')
RX=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REGX,\"reportDate\":\"$TODAY\",\"bagCount\":6,\"extraBagCount\":0,\"stackingLocation\":\"1号楼装修垃圾点\",\"wastePointId\":1,\"containsWood\":true,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":false}")
REPORT_X=$(echo "$RX" | J '.data.id')
AX=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT_X,\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"PM\",\"comment\":\"同意\"}")
APPT_X=$(echo "$AX" | J '.data.id')
check "张三1栋902同日预约(6袋木板)" "$AX" '"status":"APPROVED"'

# 车辆 GPS 停留点：先在1号楼装车，25分钟后到3号楼装车
STOP1=$(curl -s -X POST "$BASE/api/weight-reviews/stops" -H "$AUTH_COLL" -H "$CT" -d "{\"plateNo\":\"沪A12345\",\"stopTime\":\"$NOW\",\"location\":\"1号楼装修垃圾点\",\"durationMinutes\":10,\"source\":\"GPS\",\"note\":\"装车6袋\"}")
check "回传1号楼装车停留点" "$STOP1" '"durationMinutes":10'
curl -s -X POST "$BASE/api/weight-reviews/stops" -H "$AUTH_COLL" -H "$CT" -d "{\"plateNo\":\"沪A12345\",\"stopTime\":\"$(date -d '+25 minutes' +%FT%T)\",\"location\":\"3号楼临时堆放点\",\"durationMinutes\":12,\"source\":\"GPS\",\"note\":\"装车8袋\"}" > /dev/null

DM=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT_M,\"vehicleId\":1,\"plannedArrival\":\"$(date -d '+6 hours' +%FT%T)\",\"routeInfo\":\"东门进→1号楼→3号楼→处置场\"}")
DISP_M=$(echo "$DM" | J '.data.id')
check "合并车次派单(路线含两个楼栋)" "$DM" '"gatePassNo":"GP'

ENTRY_M=$(date -d '-5 minutes' +%FT%T)
WM=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP_M,\"emptyWeightKg\":5000,\"loadedWeightKg\":5600,\"entryTime\":\"$ENTRY_M\",\"exitTime\":\"$NOW\",\"photos\":\"http://img/m1.jpg,http://img/m2.jpg\",\"manifestNo\":\"MF-MERGE-001\"}")
WEIGH_M=$(echo "$WM" | J '.data.id')
check "整车净重600kg远超本户8袋预估280kg→异常" "$WM" '"anomalyFlag":true'
curl -s -X POST "$BASE/api/disposal-receipts" -H "$AUTH_COLL" -H "$CT" -d "{\"weighRecordId\":$WEIGH_M,\"disposalSite\":\"城东建筑垃圾处置场\",\"receivedWeightKg\":600}" > /dev/null
sleep 1

RV=$(curl -s -X POST "$BASE/api/weight-reviews" -H "$AUTH_PROP" -H "$CT" -d "{\"weighRecordId\":$WEIGH_M,\"note\":\"物业怀疑混装其他楼栋垃圾\",\"suspectAppointmentIds\":[]}")
REVIEW_M=$(echo "$RV" | J '.data.id')
check "发起复核自动建议合并清运" "$RV" '"suggestion":"MERGED_COLLECTION"'
RD=$(curl -s "$BASE/api/weight-reviews/$REVIEW_M" -H "$AUTH_PROP")
check "证据含装车照片" "$RD" '"http://img/m1.jpg"'
check "证据含车辆路线" "$RD" '东门进→1号楼→3号楼→处置场'
check "证据命中1号楼装车停留" "$RD" '"loadingSuspected":true'
check "证据列出同日他户预约(张三6袋)" "$RD" '"ownerName":"张三"'
check "证据含处置场回执比对" "$RD" '"receiptNo":"RC'

CF=$(curl -s -X PUT "$BASE/api/weight-reviews/$REVIEW_M/confirm" -H "$AUTH_PROP" -H "$CT" -d "{\"conclusion\":\"MERGED_COLLECTION\",\"note\":\"照片+GPS+回执三方印证，认定合并清运\",\"blockVehicle\":true,\"adjustments\":[{\"appointmentId\":$APPT_X,\"finalWeightKg\":100,\"reason\":\"装车照片显示木板较干偏轻，物业核定为100kg\"}]}")
check "复核定论合并清运" "$CF" '"conclusion":"MERGED_COLLECTION"'
check "主户(3栋8袋)拆分净重500kg" "$CF" '"confirmedWeightKg":500.00'
check "涉事车辆被暂停派单" "$CF" '"vehicleBlocked":true'

ITEMS=$(curl -s "$BASE/api/weight-reviews/$REVIEW_M" -H "$AUTH_PROP")
check "拆分明细含装车顺序" "$ITEMS" '"loadOrder":1'
check "他户人工调整为100kg并留理由" "$ITEMS" '"finalWeightKg":100.00'
check "人工调整理由已保留" "$ITEMS" '木板较干偏轻'
check "拆分按材质估算(瓷砖280kg)" "$ITEMS" '"materialEstimateKg":280.00'

CBILL_M=$(curl -s "$BASE/api/bills?type=COLLECTOR" -H "$AUTH_FIN")
check "主户清运结算按500kg拆为60元" "$(echo "$CBILL_M" | J "[.data[] | select(.appointmentId==$APPT_M)][0].amount")" '60'
check "他户补生成清运结算12元(0.1吨)" "$(echo "$CBILL_M" | J "[.data[] | select(.appointmentId==$APPT_X)][0].amount")" '12'
OBILL_X=$(curl -s "$BASE/api/bills?type=OWNER" -H "$AUTH_FIN")
check "他户补生成业主分摊账单48元(6袋×8)" "$(echo "$OBILL_X" | J "[.data[] | select(.appointmentId==$APPT_X)][0].amount")" '48'
REGXINFO=$(curl -s "$BASE/api/registrations/$REGX" -H "$AUTH_PROP")
check "他户押金抵扣48元(800→752)" "$REGXINFO" '"depositBalance":752.00'
CPLSTATUS=$(curl -s "$BASE/api/complaints" -H "$AUTH_PROP" | J "[.data[] | select(.targetAppointmentId==$APPT_M and .type==\"WEIGH_ANOMALY\")][0].status")
check "称重异常投诉随复核定论自动办结" "$CPLSTATUS" 'RESOLVED'
VLIST=$(curl -s "$BASE/api/vehicles" -H "$AUTH_COLL")
check "车辆列表显示派单限制" "$VLIST" '"dispatchBlocked":true'

# 被限制车辆不能再派单：为张三1栋902再约明日一单尝试派单
RX2=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REGX,\"reportDate\":\"$TOMORROW\",\"bagCount\":4,\"extraBagCount\":0,\"stackingLocation\":\"1号楼装修垃圾点\",\"wastePointId\":1,\"containsWood\":true,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":false}")
AX2=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$(echo "$RX2" | J '.data.id'),\"approve\":true,\"apptDate\":\"$TOMORROW\",\"timeSlot\":\"AM\",\"comment\":\"同意\"}")
APPT_X2=$(echo "$AX2" | J '.data.id')
BLOCKTRY=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT_X2,\"vehicleId\":1,\"plannedArrival\":\"$(date -d '+1 day' +%FT%T)\",\"routeInfo\":\"试派单\"}")
check "被限制车辆派单被拒" "$BLOCKTRY" '暂停派单'
UNBLK=$(curl -s -X POST "$BASE/api/weight-reviews/vehicles/1/unblock" -H "$AUTH_PROP" -H "$CT" -d '{"reason":"整改完成，恢复派单"}')
check "物业解除车辆派单限制" "$UNBLK" '"dispatchBlocked":false'
DISP_OK=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT_X2,\"vehicleId\":1,\"plannedArrival\":\"$(date -d '+1 day +2 hours' +%FT%T)\",\"routeInfo\":\"恢复后派单\"}")
check "解除限制后派单成功" "$DISP_OK" '"gatePassNo":"GP'
CLOSE6=$(curl -s -X POST "$BASE/api/weight-reviews/$REVIEW_M/close" -H "$AUTH_PROP" -H "$CT" -d '{"note":"费用拆分与车辆整改均已落实，归档"}')
check "复核单结案归档" "$CLOSE6" '"status":"CLOSED"'
CLOSE6DENY=$(curl -s -X POST "$BASE/api/weight-reviews/$REVIEW_M/close" -H "$AUTH_PROP" -H "$CT" -d '{"note":"重复结案"}')
check "已归档复核单不能重复结案" "$CLOSE6DENY" '仅已定论'

echo "== 7. 称重差异复核：疑似混装证据不足→补证重新分析→认定合并清运 =="
REGW=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"3栋\",\"roomNumber\":\"101\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+15 days' +%F)\",\"wasteTypes\":\"瓷砖\",\"frequency\":\"DAILY\",\"depositAmount\":400}" | J '.data.id')
RW=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REGW,\"reportDate\":\"$TODAY\",\"bagCount\":5,\"extraBagCount\":0,\"stackingLocation\":\"3号楼临时堆放点\",\"wastePointId\":3,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":true,\"containsMixedHousehold\":false}")
AW=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$(echo "$RW" | J '.data.id'),\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"PM\",\"comment\":\"同意\"}")
APPT_W=$(echo "$AW" | J '.data.id')
DW=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT_W,\"vehicleId\":2,\"plannedArrival\":\"$(date -d '+14 hours' +%FT%T)\",\"routeInfo\":\"东门→3号楼\"}")
WW=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$(echo "$DW" | J '.data.id'),\"emptyWeightKg\":5000,\"loadedWeightKg\":5320,\"entryTime\":\"$ENTRY_M\",\"exitTime\":\"$NOW\",\"photos\":\"http://img/w.jpg\"}")
WEIGH_W=$(echo "$WW" | J '.data.id')
RVW=$(curl -s -X POST "$BASE/api/weight-reviews" -H "$AUTH_PROP" -H "$CT" -d "{\"weighRecordId\":$WEIGH_W,\"note\":\"无回执无他楼停留证据\"}")
REVIEW_W=$(echo "$RVW" | J '.data.id')
check "仅有重量偏差时建议疑似混装(待补证)" "$RVW" '"suggestion":"SUSPICIOUS_MIXED"'
SUSDENY=$(curl -s -X PUT "$BASE/api/weight-reviews/$REVIEW_W/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"conclusion":"SUSPICIOUS_MIXED","note":"想直接挂起","blockVehicle":false,"adjustments":[]}')
check "证据不足不能定论为疑似混装" "$SUSDENY" '证据不足'

# 物业排查发现张三3栋402同日4袋木板也由该车运走：补登记/预约 + 人工补录停留点 → 重新分析
REGQ=$(curl -s -X POST "$BASE/api/registrations" -H "$AUTH_OWNER" -H "$CT" -d "{\"building\":\"3栋\",\"roomNumber\":\"402\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+15 days' +%F)\",\"wasteTypes\":\"木板\",\"frequency\":\"DAILY\",\"depositAmount\":300}" | J '.data.id')
RQ=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REGQ,\"reportDate\":\"$TODAY\",\"bagCount\":4,\"extraBagCount\":0,\"stackingLocation\":\"1号楼装修垃圾点\",\"wastePointId\":1,\"containsWood\":true,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":false}")
AQ=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$(echo "$RQ" | J '.data.id'),\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"PM\",\"comment\":\"同意\"}")
APPT_Q=$(echo "$AQ" | J '.data.id')
curl -s -X POST "$BASE/api/weight-reviews/stops" -H "$AUTH_PROP" -H "$CT" -d "{\"plateNo\":\"沪B67890\",\"stopTime\":\"$(date -d '-20 minutes' +%FT%T)\",\"location\":\"1号楼装修垃圾点\",\"durationMinutes\":8,\"source\":\"MANUAL\",\"note\":\"监控确认装车4袋\"}" > /dev/null
RA=$(curl -s -X PUT "$BASE/api/weight-reviews/$REVIEW_W/reanalyze" -H "$AUTH_PROP" -H "$CT" -d '{"note":"补证：监控+门岗记录确认"}')
check "补证重新分析后建议合并清运" "$RA" '"suggestion":"MERGED_COLLECTION"'
CW=$(curl -s -X PUT "$BASE/api/weight-reviews/$REVIEW_W/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"conclusion":"MERGED_COLLECTION","note":"补证充分，认定合并清运","blockVehicle":false,"adjustments":[]}')
check "补证后定论合并清运(主户5瓷砖袋拆得226.72kg)" "$CW" '"confirmedWeightKg":226.72'
check "补录户业主分摊账单32元(4袋×8)" "$(curl -s "$BASE/api/bills?type=OWNER" -H "$AUTH_FIN" | J "[.data[] | select(.appointmentId==$APPT_Q)][0].amount")" '32'
check "补录户预约状态置完成" "$(curl -s "$BASE/api/appointments/$APPT_Q" -H "$AUTH_PROP")" '"status":"COMPLETED"'
check "补证户押金抵扣32元(300→268)" "$(curl -s "$BASE/api/registrations/$REGQ" -H "$AUTH_PROP")" '"depositBalance":268.00'

echo "== 8. 称重差异复核：称重设备异常 / 司机误填（以处置场回执为准） =="
REGY=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"2栋\",\"roomNumber\":\"603\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+15 days' +%F)\",\"wasteTypes\":\"玻璃\",\"frequency\":\"DAILY\",\"depositAmount\":600}" | J '.data.id')
RY=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REGY,\"reportDate\":\"$TODAY\",\"bagCount\":6,\"extraBagCount\":0,\"stackingLocation\":\"2号楼垃圾点\",\"wastePointId\":2,\"containsWood\":false,\"containsGlass\":true,\"containsTile\":false,\"containsMixedHousehold\":false}")
AY=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$(echo "$RY" | J '.data.id'),\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"PM\",\"comment\":\"同意\"}")
APPT_Y=$(echo "$AY" | J '.data.id')
DY=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT_Y,\"vehicleId\":2,\"plannedArrival\":\"$(date -d '+2 hours' +%FT%T)\",\"routeInfo\":\"南门→2号楼\"}")
WY=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$(echo "$DY" | J '.data.id'),\"emptyWeightKg\":6000,\"loadedWeightKg\":6500,\"entryTime\":\"$ENTRY_M\",\"exitTime\":\"$NOW\",\"photos\":\"http://img/y.jpg\",\"manifestNo\":\"MF-SCALE-001\"}")
WEIGH_Y=$(echo "$WY" | J '.data.id')
curl -s -X POST "$BASE/api/disposal-receipts" -H "$AUTH_COLL" -H "$CT" -d "{\"weighRecordId\":$WEIGH_Y,\"disposalSite\":\"城南处置场\",\"receivedWeightKg\":300}" > /dev/null
sleep 1
RVY=$(curl -s -X POST "$BASE/api/weight-reviews" -H "$AUTH_PROP" -H "$CT" -d "{\"weighRecordId\":$WEIGH_Y,\"note\":\"地磅与处置场磅差异常\"}")
check "地磅500kg/回执300kg且均偏离预估→建议设备异常" "$RVY" '"suggestion":"SCALE_MALFUNCTION"'
REVIEW_Y=$(echo "$RVY" | J '.data.id')
CFY=$(curl -s -X PUT "$BASE/api/weight-reviews/$REVIEW_Y/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"conclusion":"SCALE_MALFUNCTION","note":"地磅校准故障，以处置场磅为准","blockVehicle":false,"adjustments":[]}')
check "设备异常核定重量300kg" "$CFY" '"confirmedWeightKg":300.00'
check "清运结算由60元调整为36元" "$(curl -s "$BASE/api/bills?type=COLLECTOR" -H "$AUTH_FIN" | J "[.data[] | select(.appointmentId==$APPT_Y)][0].amount")" '36'

REGZ=$(curl -s -X POST "$BASE/api/registrations" -H "$AUTH_OWNER" -H "$CT" -d "{\"building\":\"1栋\",\"roomNumber\":\"707\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+15 days' +%F)\",\"wasteTypes\":\"其他\",\"frequency\":\"DAILY\",\"depositAmount\":500}" | J '.data.id')
RZ=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REGZ,\"reportDate\":\"$TODAY\",\"bagCount\":4,\"extraBagCount\":0,\"stackingLocation\":\"1号楼垃圾点旁\",\"wastePointId\":1,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":false}")
AZ=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$(echo "$RZ" | J '.data.id'),\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"PM\",\"comment\":\"同意\"}")
APPT_Z=$(echo "$AZ" | J '.data.id')
DZ=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT_Z,\"vehicleId\":2,\"plannedArrival\":\"$(date -d '+8 hours' +%FT%T)\",\"routeInfo\":\"南门→1号楼\"}")
WZ=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$(echo "$DZ" | J '.data.id'),\"emptyWeightKg\":5000,\"loadedWeightKg\":5300,\"entryTime\":\"$ENTRY_M\",\"exitTime\":\"$NOW\",\"photos\":\"http://img/z.jpg\",\"manifestNo\":\"MF-DRIVER-001\"}")
WEIGH_Z=$(echo "$WZ" | J '.data.id')
curl -s -X POST "$BASE/api/disposal-receipts" -H "$AUTH_COLL" -H "$CT" -d "{\"weighRecordId\":$WEIGH_Z,\"disposalSite\":\"城东处置场\",\"receivedWeightKg\":102}" > /dev/null
sleep 1
RVZ=$(curl -s -X POST "$BASE/api/weight-reviews" -H "$AUTH_PROP" -H "$CT" -d "{\"weighRecordId\":$WEIGH_Z,\"note\":\"回执与预估吻合，疑司机误填\"}")
check "回执102kg≈预估100kg→建议司机误填" "$RVZ" '"suggestion":"DRIVER_MISENTRY"'
CFZ=$(curl -s -X PUT "$BASE/api/weight-reviews/$(echo "$RVZ" | J '.data.id')/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"conclusion":"DRIVER_MISENTRY","note":"司机空车重量录入错误，按回执102kg结算","blockVehicle":false,"adjustments":[]}')
check "司机误填核定102kg" "$CFZ" '"confirmedWeightKg":102.00'
check "清运结算调整为12.24元(0.102吨)" "$(curl -s "$BASE/api/bills?type=COLLECTOR" -H "$AUTH_FIN" | J "[.data[] | select(.appointmentId==$APPT_Z)][0].amount")" '12.24'

DP2=$(curl -s "$BASE/api/dashboard/property" -H "$AUTH_PROP")
check "物业看板-待复核统计" "$DP2" '"pendingWeightReviews"'
check "物业看板-合并清运复核数" "$DP2" '"mergedCollectionReviews"'

echo ""
echo "==================================="
echo "验证结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" -eq 0 ] && echo "ALL CHECKS PASSED" || exit 1
