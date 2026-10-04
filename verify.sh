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

echo "== 6. 称重差异复核（合并清运）：GPS停留点→异常自动立案→照片证据→确认拆账→账单/押金/车辆冻结 =="
T_3H=$(date -d "3 hours ago" +%FT%T)
T_5M=$(date -d "5 minutes ago" +%FT%T)
T_NOW2=$(date +%FT%T)

# 3栋303业主登记(押金1000) + 上报10袋瓷砖 + 物业审核通过（3号点 5+10=15/30）
REG3=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"3栋\",\"roomNumber\":\"303\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+20 days' +%F)\",\"wasteTypes\":\"瓷砖\",\"frequency\":\"WEEKLY\",\"depositAmount\":1000}" | J '.data.id')
check "3栋303登记(押金1000)" "$([ -n "$REG3" ] && [ "$REG3" != "null" ] && echo ok)" "ok"
R4=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG3,\"reportDate\":\"$TODAY\",\"bagCount\":10,\"extraBagCount\":0,\"stackingLocation\":\"3号楼临时堆放点\",\"wastePointId\":3,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORT4=$(echo "$R4" | J '.data.id')
A4=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT4,\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"AM\",\"comment\":\"同意清运\"}")
APPT4=$(echo "$A4" | J '.data.id')
check "3栋303预约审核通过" "$A4" '"status":"APPROVED"'

# 车2给3栋303派单（计划到场 3 小时前），司机准点到场
D4=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT4,\"vehicleId\":2,\"plannedArrival\":\"$T_3H\",\"routeInfo\":\"南门进→3号楼→1号楼→东门出\"}")
DISP4=$(echo "$D4" | J '.data.id')
check "车2给3栋303派单" "$D4" '"status":"DISPATCHED"'
curl -s -X POST "$BASE/api/dispatches/$DISP4/arrive" -H "$AUTH_COLL" -H "$CT" -d "{\"actualArrival\":\"$T_3H\"}" >/dev/null
# GPS：车在3号楼点装车停留12分钟
STOP1=$(curl -s -X POST "$BASE/api/vehicle-stops" -H "$AUTH_COLL" -H "$CT" -d "{\"plateNo\":\"沪B67890\",\"eventTime\":\"$T_3H\",\"location\":\"3号楼北侧围挡区垃圾点\",\"wastePointId\":3,\"stayMinutes\":12,\"photos\":\"http://img/3bldg-load.jpg\"}")
check "GPS停留点(3号楼装车12分钟)" "$STOP1" '"stayMinutes":12'

# 4栋101业主登记(押金500) + 上报2袋木板（明日上报允许）+ 审核为今日 AM（1号点 20+2=22/100）
REG4=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"4栋\",\"roomNumber\":\"101\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+20 days' +%F)\",\"wasteTypes\":\"木板\",\"frequency\":\"WEEKLY\",\"depositAmount\":500}" | J '.data.id')
R5=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG4,\"reportDate\":\"$TOMORROW\",\"bagCount\":2,\"extraBagCount\":0,\"stackingLocation\":\"1号楼东侧空地临时堆放\",\"wastePointId\":1,\"containsWood\":true,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":false}")
REPORT5=$(echo "$R5" | J '.data.id')
A5=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT5,\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"AM\",\"comment\":\"2袋木板，同车捎带\"}")
APPT5=$(echo "$A5" | J '.data.id')
check "4栋101(2袋木板)预约通过" "$A5" '"status":"APPROVED"'
# 同车2派单（5分钟前，与D4相隔3小时，避开 ±2h 冲突；该单司机不单独过磅）
D5=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT5,\"vehicleId\":2,\"plannedArrival\":\"$T_5M\",\"routeInfo\":\"3号楼→1号楼→处置场\"}")
DISP5=$(echo "$D5" | J '.data.id')
check "同车2给4栋101派单(间隔3小时无冲突)" "$D5" '"status":"DISPATCHED"'
STOP2=$(curl -s -X POST "$BASE/api/vehicle-stops" -H "$AUTH_COLL" -H "$CT" -d "{\"plateNo\":\"沪B67890\",\"eventTime\":\"$T_5M\",\"location\":\"1号楼东侧垃圾点\",\"wastePointId\":1,\"stayMinutes\":8,\"photos\":\"http://img/1bldg-load.jpg\"}")
check "GPS停留点(1号楼装车8分钟)" "$STOP2" '"stayMinutes":8'

# 司机把两批合并过磅：10袋瓷砖估300kg(30kg/袋)+2袋木板估36kg(18kg/袋)=336kg；主户按袋预估250kg，偏差34.4%→异常
W3=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP4,\"emptyWeightKg\":5000,\"loadedWeightKg\":5336,\"entryTime\":\"$T_NOW2\",\"exitTime\":\"$T_NOW2\",\"photos\":\"http://img/merged-1.jpg,http://img/merged-2.jpg\",\"manifestNo\":\"MF-MERGED-001\"}")
WEIGH3=$(echo "$W3" | J '.data.id')
check "合并过磅净重336kg(主户估250偏差34%)→异常" "$W3" '"anomalyFlag":true'
sleep 1
REVIEWS0=$(curl -s "$BASE/api/weigh-reviews?status=PENDING" -H "$AUTH_PROP")
check "异常后自动立称重复核案" "$REVIEWS0" '"reviewNo":"WR'
REV_ID=$(echo "$REVIEWS0" | J '[.data[] | select(.weighRecordId=='"$WEIGH3"')][0].id')
check "复核单关联主预约3栋303" "$(echo "$REVIEWS0" | jq -c '[.data[] | select(.id=='"$REV_ID"')][0].appointmentId')" "$APPT4"
check "系统已识别同车同日其他预约" "$REVIEWS0" "APT"

# 补传装车照片证据：清点12袋(=10+2)，标签含3栋、4栋
PE=$(curl -s -X POST "$BASE/api/weigh-reviews/$REV_ID/photo-evidence" -H "$AUTH_PROP" -H "$CT" -d '{"photoVisibleBags":12,"photoLabels":"3栋303,4栋101"}')
check "照片证据后建议=合并清运" "$PE" '"systemSuggestion":"MERGED_COLLECTION"'
SHARES_PRE=$(curl -s "$BASE/api/weigh-reviews/$REV_ID" -H "$AUTH_PROP")
check "拆分明细-3栋303" "$SHARES_PRE" '"buildingRoom":"3栋303"'
check "拆分明细-4栋101" "$SHARES_PRE" '"buildingRoom":"4栋101"'
check "3栋303先装车顺序1" "$(echo "$SHARES_PRE" | J '[.data.shares[] | select(.buildingRoom=="3栋303")][0].loadSequence')" "1"
check "4栋101后装车顺序2(尾差户)" "$(echo "$SHARES_PRE" | J '[.data.shares[] | select(.buildingRoom=="4栋101")][0].loadSequence')" "2"
check "系统拆分合计=净重336kg" "$(echo "$SHARES_PRE" | J '[.data.shares[].confirmedWeightKg] | add')" "336"
check "拆分重量-3栋300kg" "$(echo "$SHARES_PRE" | jq -c '[.data.shares[] | select(.buildingRoom=="3栋303")][0] | {confirmedWeightKg}')" '"confirmedWeightKg":300'
check "拆分重量-4栋36kg" "$(echo "$SHARES_PRE" | jq -c '[.data.shares[] | select(.buildingRoom=="4栋101")][0] | {confirmedWeightKg}')" '"confirmedWeightKg":36'
check "拆分清运结算合计=40.32元" "$(echo "$SHARES_PRE" | J '[.data.shares[].collectorFee] | add')" "40.32"

# 无理由逐户调整 → 拒绝
BADADJ=$(curl -s -X POST "$BASE/api/weigh-reviews/$REV_ID/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"shareAdjusts":[{"appointmentId":'"$APPT5"',"confirmedWeightKg":100.00,"reason":""}]}')
check "无理由逐户调整被拒" "$BADADJ" "理由"
# 拆账合计不等于净重 → 拒绝（需提供确认级理由才能进入合计校验）
BADADJ2=$(curl -s -X POST "$BASE/api/weigh-reviews/$REV_ID/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"reason":"人工重新分配","shareAdjusts":[{"appointmentId":'"$APPT5"',"confirmedWeightKg":100.00,"reason":"测试错账"}]}')
check "拆账合计与净重不一致被拒" "$BADADJ2" "不一致"

# 确认合并清运（采纳系统拆分，必填理由）
CFM=$(curl -s -X POST "$BASE/api/weigh-reviews/$REV_ID/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"reason":"GPS两处装车停留+照片标签+同车两单，确认3栋与4栋合并清运，按材质估算与装车顺序拆账"}')
check "物业确认合并清运" "$CFM" '"finalConclusion":"MERGED_COLLECTION"'
check "复核单已确认" "$CFM" '"status":"CONFIRMED"'
check "采纳系统拆分(未人工调整)" "$CFM" '"manualAdjusted":false'
check "物业调整理由已留存" "$CFM" "按材质估算与装车顺序拆账"
check "车2默认冻结派单" "$(curl -s "$BASE/api/vehicles" -H "$AUTH_PROP" | J '[.data[] | select(.id==2)][0].dispatchSuspended')" "true"
sleep 1
check "被合并户4栋101补建业主账单16元" "$(curl -s "$BASE/api/bills?type=OWNER" -H "$AUTH_FIN" | jq -c '[.data[] | select(.appointmentId=='"$APPT5"')][0] | {amount,merged:(.detail|test("mergedCollection.:true"))}')" '"amount":16'
check "4栋101押金500-16=484" "$(curl -s "$BASE/api/registrations/$REG4" -H "$AUTH_PROP")" '"depositBalance":484.00'
check "3栋303按袋计费80元押金1000-80=920" "$(curl -s "$BASE/api/registrations/$REG3" -H "$AUTH_PROP")" '"depositBalance":920.00'
check "3号垃圾点库存恢复5袋" "$(curl -s "$BASE/api/waste-points" -H "$AUTH_PROP" | J '[.data[] | select(.id==3)][0].currentBags')" "5"
check "1号垃圾点释放2袋(22→20)" "$(curl -s "$BASE/api/waste-points" -H "$AUTH_PROP" | J '[.data[] | select(.id==1)][0].currentBags')" "20"
check "称重异常投诉自动办结" "$(curl -s "$BASE/api/complaints" -H "$AUTH_PROP" | J '[.data[] | select(.targetAppointmentId=='"$APPT4"' and .type=="WEIGH_ANOMALY")][0].status')" "RESOLVED"
check "被合并户派单补齐完成" "$(curl -s "$BASE/api/dispatches" -H "$AUTH_PROP" | J '[.data[] | select(.id=='"$DISP5"')][0].status')" "COMPLETED"

# 冻结车辆派单拦截：再造一个已审核未派单预约
REG5=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"5栋\",\"roomNumber\":\"202\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+20 days' +%F)\",\"wasteTypes\":\"木板\",\"frequency\":\"WEEKLY\",\"depositAmount\":300}" | J '.data.id')
R6=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG5,\"reportDate\":\"$TOMORROW\",\"bagCount\":3,\"extraBagCount\":0,\"stackingLocation\":\"3号楼临时堆放点旁\",\"wastePointId\":3,\"containsWood\":true,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":false}")
REPORT6=$(echo "$R6" | J '.data.id')
A6=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT6,\"approve\":true,\"apptDate\":\"$TOMORROW\",\"timeSlot\":\"PM\",\"comment\":\"\"}")
APPT6=$(echo "$A6" | J '.data.id')
check "5栋202预约通过(用于冻结拦截验证)" "$A6" '"status":"APPROVED"'
FROZEN_DISP=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT6,\"vehicleId\":2,\"plannedArrival\":\"$(date -d '+1 day' +%FT%T)\"}")
check "冻结车辆派单被拦截" "$FROZEN_DISP" "暂停派单权限"
check "无核查说明解冻被拒" "$(curl -s -X POST "$BASE/api/vehicles/2/unsuspend" -H "$AUTH_PROP" -H "$CT" -d '{"reason":""}')" "must not be blank"
UNS=$(curl -s -X POST "$BASE/api/vehicles/2/unsuspend" -H "$AUTH_PROP" -H "$CT" -d '{"reason":"约谈绿洁清运及陈师傅，系调度私下拼单，已整改培训，恢复派单并跟踪一周"}')
check "物业核查后解冻车辆" "$UNS" '"dispatchSuspended":false'
OKDISP=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT6,\"vehicleId\":2,\"plannedArrival\":\"$(date -d '+1 day' +%FT%T)\"}")
check "解冻后派单成功" "$OKDISP" '"status":"DISPATCHED"'

# 追溯还原
TRACE4=$(curl -s "$BASE/api/trace/appointments/$APPT4" -H "$AUTH_PROP")
check "主户追溯含复核单" "$TRACE4" '"weighReviews"'
check "主户追溯含REVIEW_SPLIT拆账事件" "$TRACE4" "REVIEW_SPLIT"
TRACE5=$(curl -s "$BASE/api/trace/appointments/$APPT5" -H "$AUTH_PROP")
check "被合并户可追溯拆分明细" "$TRACE5" '"weighReviewShares"'
check "被合并户追溯含补建账单" "$TRACE5" "合并清运复核补建业主账单"

echo "== 7. 称重复核（设备异常 / 司机误填）：回执刷新建议→订正结算→误填冻结 =="
T_P1=$(date -d '+1 day' +%FT%T)
# 6栋404：4袋玻璃，按袋估100kg；车载回传528kg，地磅回执120kg（互证估算，指向车载秤异常）
REG6=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"6栋\",\"roomNumber\":\"404\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+20 days' +%F)\",\"wasteTypes\":\"玻璃\",\"frequency\":\"WEEKLY\",\"depositAmount\":500}" | J '.data.id')
R7=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG6,\"reportDate\":\"$TOMORROW\",\"bagCount\":4,\"extraBagCount\":0,\"stackingLocation\":\"1号楼东侧空地\",\"wastePointId\":1,\"containsWood\":false,\"containsGlass\":true,\"containsTile\":false,\"containsMixedHousehold\":false}")
REPORT7=$(echo "$R7" | J '.data.id')
A7=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT7,\"approve\":true,\"apptDate\":\"$TOMORROW\",\"timeSlot\":\"AM\",\"comment\":\"\"}")
APPT7=$(echo "$A7" | J '.data.id')
D7=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT7,\"vehicleId\":1,\"plannedArrival\":\"$T_P1\"}")
DISP7=$(echo "$D7" | J '.data.id')
W4=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP7,\"emptyWeightKg\":5000,\"loadedWeightKg\":5528,\"entryTime\":\"$T_P1\",\"exitTime\":\"$T_P1\",\"photos\":\"http://img/dev.jpg\",\"manifestNo\":\"MF-DEV-001\"}")
WEIGH4=$(echo "$W4" | J '.data.id')
check "设备异常场景称重528kg(估100)→异常立案" "$W4" '"anomalyFlag":true'
sleep 1
REV1=$(curl -s "$BASE/api/weigh-reviews?status=PENDING" -H "$AUTH_PROP" | J '[.data[] | select(.weighRecordId=='"$WEIGH4"')][0].id')
check "无回执无GPS时建议证据不足" "$(curl -s "$BASE/api/weigh-reviews/$REV1" -H "$AUTH_PROP")" '"systemSuggestion":"INCONCLUSIVE"'
# 无理由认定证据不足 → 拒绝
check "证据不足结论无说明被拒" "$(curl -s -X POST "$BASE/api/weigh-reviews/$REV1/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"conclusion":"INCONCLUSIVE"}')" "情况说明"
# 补传地磅回执 120kg → 自动刷新为设备异常
RC2=$(curl -s -X POST "$BASE/api/disposal-receipts" -H "$AUTH_COLL" -H "$CT" -d "{\"weighRecordId\":$WEIGH4,\"disposalSite\":\"城东建筑垃圾处置场\",\"receivedWeightKg\":120}")
check "补传处置回执120kg" "$RC2" '"receiptNo":"RC'
check "回执互证后刷新建议=设备异常" "$(curl -s "$BASE/api/weigh-reviews/$REV1" -H "$AUTH_PROP")" '"systemSuggestion":"DEVICE_ANOMALY"'
# 无拆账预案却推翻为合并清运 → 拒绝
check "无证据推翻为合并清运被拒" "$(curl -s -X POST "$BASE/api/weigh-reviews/$REV1/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"conclusion":"MERGED_COLLECTION","reason":"硬改"}')" "拆分预案"
CFM2=$(curl -s -X POST "$BASE/api/weigh-reviews/$REV1/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"reason":"现场复称120kg，系车载秤传感器漂移，按地磅120kg结算"}')
check "确认称重设备异常" "$CFM2" '"finalConclusion":"DEVICE_ANOMALY"'
check "设备异常确认重量=回执120kg" "$CFM2" '"confirmedWeightKg":120.00'
sleep 1
CBILL2=$(curl -s "$BASE/api/bills?type=COLLECTOR" -H "$AUTH_FIN" | jq -c '[.data[] | select(.appointmentId=='"$APPT7"')][0]')
check "清运结算订正63.36→14.40元" "$CBILL2" '"amount":14.4'
check "订正后结算单待财务处理(UNPAID)" "$CBILL2" '"status":"UNPAID"'
check "车1设备异常不默认冻结" "$(curl -s "$BASE/api/vehicles" -H "$AUTH_PROP" | J '[.data[] | select(.id==1)][0].dispatchSuspended')" "false"

# 7栋505：8袋(含1加袋)，按袋估200kg；车载400kg，照片仅见本户8袋、无GPS/回执 → 司机误填
T_P2=$(date -d '+2 days' +%FT%T)
REG7=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"7栋\",\"roomNumber\":\"505\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+20 days' +%F)\",\"wasteTypes\":\"瓷砖,木板\",\"frequency\":\"WEEKLY\",\"depositAmount\":500}" | J '.data.id')
R8=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG7,\"reportDate\":\"$TOMORROW\",\"bagCount\":8,\"extraBagCount\":1,\"stackingLocation\":\"1号楼东侧空地\",\"wastePointId\":1,\"containsWood\":true,\"containsGlass\":false,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORT8=$(echo "$R8" | J '.data.id')
A8=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT8,\"approve\":true,\"apptDate\":\"$(date -d '+2 days' +%F)\",\"timeSlot\":\"PM\",\"comment\":\"\"}")
APPT8=$(echo "$A8" | J '.data.id')
D8=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT8,\"vehicleId\":1,\"plannedArrival\":\"$T_P2\"}")
DISP8=$(echo "$D8" | J '.data.id')
W5=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP8,\"emptyWeightKg\":5000,\"loadedWeightKg\":5400,\"entryTime\":\"$T_P2\",\"exitTime\":\"$T_P2\",\"photos\":\"http://img/mis.jpg\",\"manifestNo\":\"MF-MIS-001\"}")
WEIGH5=$(echo "$W5" | J '.data.id')
check "误填场景称重400kg(估200)→异常" "$W5" '"anomalyFlag":true'
sleep 1
REV2=$(curl -s "$BASE/api/weigh-reviews?status=PENDING" -H "$AUTH_PROP" | J '[.data[] | select(.weighRecordId=='"$WEIGH5"')][0].id')
PE2=$(curl -s -X POST "$BASE/api/weigh-reviews/$REV2/photo-evidence" -H "$AUTH_PROP" -H "$CT" -d '{"photoVisibleBags":8,"photoLabels":"7栋505"}')
check "照片仅本户8袋且无其他证据→建议司机误填" "$PE2" '"systemSuggestion":"DRIVER_MISENTRY"'
CFM3=$(curl -s -X POST "$BASE/api/weigh-reviews/$REV2/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"reason":"核对联单底单，司机录重量时档位错误，按验收8袋折算200kg"}')
check "确认司机误填" "$CFM3" '"finalConclusion":"DRIVER_MISENTRY"'
check "误填确认重量=按袋估算200kg" "$CFM3" '"confirmedWeightKg":200.00'
sleep 1
CBILL3=$(curl -s "$BASE/api/bills?type=COLLECTOR" -H "$AUTH_FIN" | jq -c '[.data[] | select(.appointmentId=='"$APPT8"')][0]')
check "清运结算订正48→24元" "$CBILL3" '"amount":24'
check "司机误填默认冻结车1" "$(curl -s "$BASE/api/vehicles" -H "$AUTH_PROP" | J '[.data[] | select(.id==1)][0].dispatchSuspended')" "true"

# 看板联动复核数据
# 先办结流程2遗留的 W2 复核单（2号楼10袋瓷砖密度大，物业复核无异常，需填理由推翻系统"证据不足"建议）
REV_W2=$(curl -s "$BASE/api/weigh-reviews?status=PENDING" -H "$AUTH_PROP" | J '[.data[] | select(.appointmentId==3)][0].id')
CFM_W2=$(curl -s -X POST "$BASE/api/weigh-reviews/$REV_W2/confirm" -H "$AUTH_PROP" -H "$CT" -d '{"conclusion":"NO_ANOMALY","reason":"现场逐袋清点确为10袋，系瓷砖混凝土密度大导致超重，照片与回执一致，维持原计量与结算"}')
check "2号楼历史异常复核为无异常(人工推翻留理由)" "$CFM_W2" '"finalConclusion":"NO_ANOMALY"'
check "该单记录为人工调整" "$CFM_W2" '"manualAdjusted":true'

DF2=$(curl -s "$BASE/api/dashboard/finance?period=$PERIOD" -H "$AUTH_FIN")
check "财务看板-复核统计合并清运1" "$DF2" '"mergedCollection":1'
check "财务看板-复核统计设备异常1" "$DF2" '"deviceAnomaly":1'
check "财务看板-复核统计司机误填1" "$DF2" '"driverMisentry":1'
DP2=$(curl -s "$BASE/api/dashboard/property" -H "$AUTH_PROP")
check "物业看板-冻结车辆清单" "$DP2" '"suspendedVehicles"'
check "物业看板-待复核数量字段" "$DP2" '"pendingWeighReviewCount":0'
# 复核单全部办结
check "无待处理复核单" "$(curl -s "$BASE/api/weigh-reviews?status=PENDING" -H "$AUTH_PROP")" '"data":[]'
# 解冻车1收尾
check "物业凭说明解冻车1" "$(curl -s -X POST "$BASE/api/vehicles/1/unsuspend" -H "$AUTH_PROP" -H "$CT" -d '{"reason":"录单错误，司机重新培训录单规范，恢复派单"}')" '"dispatchSuspended":false'

echo ""
echo "==================================="
echo "验证结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" -eq 0 ] && echo "ALL CHECKS PASSED" || exit 1
