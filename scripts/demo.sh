#!/usr/bin/env bash
# Runs the payment flow end to end on your machine against a local WeChat Pay simulator.
# No WeChat Pay account needed. Requires Java 17+, curl and jq.
#   scripts/demo.sh              # PAUSE=0 to run without pauses, MVN=mvn to use a local Maven
set -euo pipefail
cd "$(dirname "$0")/.."

MVN="${MVN:-./mvnw}"
PAUSE="${PAUSE:-2}"
APP=http://localhost:8080
SIM=http://localhost:8089
OUT=target/demo
FAILURES=0

bold() { printf '\n\033[1;36m━━ %s\033[0m\n' "$*"; }
say()  { printf '\033[0;37m%s\033[0m\n' "$*"; }
cmd()  { printf '\033[1;33m$ %s\033[0m\n' "$*"; }

SIM_SEEN=0
APP_SEEN=0
logs() {
    sleep 0.5
    local n
    n=$(wc -l < "$OUT/simulator.log")
    if (( n > SIM_SEEN )); then
        sed -n "$((SIM_SEEN + 1)),${n}p" "$OUT/simulator.log" | sed 's/^/    \x1b[35m[WeChat Pay]\x1b[0m /'
    fi
    SIM_SEEN=$n
    n=$(wc -l < "$OUT/app.log")
    if (( n > APP_SEEN )); then
        sed -n "$((APP_SEEN + 1)),${n}p" "$OUT/app.log" | grep -E 'PaymentService|ApiExceptionHandler' \
            | sed -E 's/.* : //; s/^/    \x1b[34m[platform]\x1b[0m /' || true
    fi
    APP_SEEN=$n
}

# call METHOD URL [JSON] -> prints status + pretty body, stores body in $BODY and status in $CODE
call() {
    local method=$1 url=$2 data=${3:-}
    if [[ -n $data ]]; then
        cmd "curl -X $method $url -d '$(jq -c . <<< "$data")'"
        RESP=$(curl -s -X "$method" "$url" -H 'Content-Type: application/json' -d "$data" -w $'\n%{http_code}')
    else
        cmd "curl -X $method $url"
        RESP=$(curl -s -X "$method" "$url" -w $'\n%{http_code}')
    fi
    CODE=${RESP##*$'\n'}
    BODY=${RESP%$'\n'*}
    printf 'HTTP %s\n' "$CODE"
    [[ -n $BODY ]] && jq -C . <<< "$BODY" 2>/dev/null || true
    logs
}

check() { # description actual expected
    if [[ "$2" == "$3" ]]; then
        printf '\033[1;32m✔ %s (%s)\033[0m\n' "$1" "$2"
    else
        printf '\033[1;31m✘ %s: expected %s, got %s\033[0m\n' "$1" "$3" "$2"
        FAILURES=$((FAILURES + 1))
    fi
    sleep "$PAUSE"
}

status_of() { curl -s "$APP/api/payments/$1" | jq -r .status; }

create() { # merchantOrderId amount
    call POST "$APP/api/payments" "{\"merchantOrderId\":\"$1\",\"amount\":$2,\"currency\":\"CNY\",\"description\":\"Coffee beans 1kg\",\"provider\":\"wechatpay\"}"
    ID=$(jq -r .id <<< "$BODY")
}

cleanup() { kill "${APP_PID:-}" "${SIM_PID:-}" 2>/dev/null || true; }
trap cleanup EXIT

bold "Building (first run downloads dependencies)"
$MVN -q -B -DskipTests package
$MVN -q -B test-compile dependency:build-classpath -Dmdep.outputFile=target/demo-classpath.txt -Dmdep.includeScope=test
mkdir -p "$OUT"

bold "Starting the WeChat Pay simulator (:8089) and the payments platform (:8080)"
java -cp "target/test-classes:target/classes:$(cat target/demo-classpath.txt)" \
    com.example.payments.demo.WeChatPaySimulator 8089 "$OUT" > "$OUT/simulator.log" 2>&1 &
SIM_PID=$!
until [[ -s $OUT/wechatpay.env ]] && curl -s -o /dev/null "$SIM/simulator/pay/ready"; do sleep 0.5; done
set -a; source "$OUT/wechatpay.env"; set +a
java -jar target/wechatpay-v3-poc-0.1.0-SNAPSHOT.jar > "$OUT/app.log" 2>&1 &
APP_PID=$!
until curl -s -o /dev/null "$APP/api/payments/ping"; do sleep 0.5; done
say "Platform configured with throwaway merchant keys from $OUT/wechatpay.env"
logs

RUN=$(date +%s)

bold "1. Merchant creates a ¥19.99 payment → WeChat Pay returns a QR code link"
create "order-$RUN-1" 1999
PAID_ID=$ID
check "payment created and waiting for customer" "$(jq -r .status <<< "$BODY")" PENDING
check "customer gets a WeChat QR code" "$(jq -r .nextAction.type <<< "$BODY")" QR_CODE

bold "2. Attacker posts a fake 'payment succeeded' notification"
call POST "$SIM/simulator/forge/$PAID_ID"
check "payment NOT marked paid" "$(status_of "$PAID_ID")" PENDING

bold "3. Customer scans the QR code and pays → WeChat Pay sends a signed, encrypted notification"
call POST "$SIM/simulator/pay/$PAID_ID"
call GET "$APP/api/payments/$PAID_ID"
check "payment succeeded" "$(jq -r .status <<< "$BODY")" SUCCEEDED

bold "4. WeChat Pay retries the same notification"
call POST "$SIM/simulator/resend/$PAID_ID"
check "duplicate acknowledged, nothing changes" "$(status_of "$PAID_ID")" SUCCEEDED

bold "5. Notification lost → merchant asks WeChat Pay for the status"
create "order-$RUN-2" 4500
REFRESH_ID=$ID
call POST "$SIM/simulator/pay/$REFRESH_ID?notify=false"
check "platform does not know yet" "$(status_of "$REFRESH_ID")" PENDING
call POST "$APP/api/payments/$REFRESH_ID/refresh"
check "refresh picks up the payment" "$(jq -r .status <<< "$BODY")" SUCCEEDED

bold "6. Customer abandons checkout → merchant cancels"
create "order-$RUN-3" 999
CANCEL_ID=$ID
call POST "$APP/api/payments/$CANCEL_ID/cancel"
check "order closed at WeChat Pay" "$(jq -r .status <<< "$BODY")" CANCELLED
call POST "$APP/api/payments/$CANCEL_ID/cancel"
check "second cancel rejected" "$CODE" 409

bold "7. Invalid request never reaches WeChat Pay"
call POST "$APP/api/payments" '{"merchantOrderId":"bad","amount":0,"currency":"CNY","description":"x","provider":"wechatpay"}'
check "validation error" "$CODE" 400

echo
if (( FAILURES == 0 )); then
    printf '\033[1;32mAll checks passed.\033[0m Logs: %s/app.log, %s/simulator.log\n' "$OUT" "$OUT"
else
    printf '\033[1;31m%d check(s) failed.\033[0m Logs: %s/app.log, %s/simulator.log\n' "$FAILURES" "$OUT" "$OUT"
    exit 1
fi
