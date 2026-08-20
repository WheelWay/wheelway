/*
  사용자용 경로 화면.

  흐름은 카카오맵 길찾기를 따랐다.
    입력 → 자동완성 → 검색결과 A·B·C → 고르면 지도 이동 → 출발·도착이 차면 경로

  다른 점 둘.
    - 이동수단 탭이 없다. 이 서비스는 도보 고정이다
    - '최단거리' 같은 경로 종류 표기가 없다. 고를 경로가 하나뿐이라 이름 붙일 게 없다

  장소 검색은 카카오 지도 SDK 의 services 라이브러리를 쓴다(서버 경유 없음).
  경로 탐색만 우리 서버(/api/route)를 부른다.

  route.html 과 겹치는 부분(설정 읽기 → SDK 적재 → 지도 생성, CustomOverlay 로 핀 찍기)은
  같은 방식이다. 다만 화면에 내보내는 값이 다르다 —
  방문 노드·계산 시간·스냅 거리는 검증용 수치라 여기에는 두지 않는다.
*/

'use strict';

// 지도 초기 위치. application.properties 의 wheelway.region-id 와 맞춰야 한다.
// chungbuk-boeun 으로 바꿀 때 → [36.4894, 127.7295] (보은읍 중심가)
/*
  지도를 처음 띄울 자리.

  ★ 좌표를 박아두지 않는다. region-id 를 보은으로 바꾸면 서버가 주는 bounds 도 같이 바뀌는데,
  중심만 시청에 남아 있으면 열자마자 데이터가 없는 곳이 뜬다(실제로 그랬다).
  bounds 한가운데를 쓰면 지역을 갈아탈 때 화면이 저절로 따라온다.

  아래 값은 bounds 를 못 받았을 때만 쓰는 마지막 수단이다.
*/
const CENTER_FALLBACK = [37.5663, 126.9779];   // 서울시청

function initialCenter() {
    if (!serviceBounds) {
        return CENTER_FALLBACK;
    }
    const [minLat, minLng, maxLat, maxLng] = serviceBounds;
    return [(minLat + maxLat) / 2, (minLng + maxLng) / 2];
}

// 휠체어 속도는 개인차가 커서 안내 문구에도 '약'을 남긴다.
//
// 66.7 = 4km/h 인데 이건 비장애인 보행 속도다. 이제는 시작값일 뿐이고,
// 서버가 /api/walk/speed 로 그 사람의 실측 중앙값을 내려주면 그것으로 갈아탄다.
// 서버가 원본을 갖는다 — 두 곳에 두면 한쪽만 고쳐 안내가 갈린다.
let WALK_M_PER_MIN = 66.7;

/** 속도가 개인 실측에서 나온 것인가. 화면에 출처를 적어야 해서 들고 있는다. */
let speedInfo = { personalized: false, recordCount: 0, minRecords: 3 };

/**
 * 로그인한 아이디. 비어 있으면 비로그인이다. {@code /api/config} 가 준다.
 *
 * <p>기록 기능은 <b>로그인 전용</b>이다 — WALK_RECORDS 가 USERS 로 FK 가 걸려 있어
 * 애초에 익명으로는 남길 수 없다. 화면이 이걸 미리 알아야 '눌러봐야 비로소 거절당하는'
 * 흐름을 피할 수 있다.
 */
let loginId = '';

/** 진행 중인 이동. [출발] 을 누른 뒤 브라우저를 닫아도 이어지도록 여기에 둔다. */
const TRACK_KEY = 'wheelway.tracking';

const SUGGEST_SIZE = 10;   // 자동완성에 띄울 개수
const RESULT_SIZE  = 15;   // 검색결과 한 쪽(카카오 최대치)
const RECENT_KEY   = 'wheelway.recent';
const RECENT_MAX   = 5;

let map = null;
let places = null;          // kakao.maps.services.Places

// 확정된 출발·도착. {name, lat, lng}
const picked = { start: null, end: null };

let startPin = null, endPin = null, routeLine = null;
let resultMarkers = [];     // 검색결과 A·B·C 마커
let pagination = null;      // 카카오가 주는 페이지 객체
let searchState = { query: '', field: null, items: [] };

const $ = (id) => document.getElementById(id);

function setStatus(text, kind) {
    const el = $('status');
    el.textContent = text;
    el.className = 'status' + (kind ? ' is-' + kind : '');
}

/* ── 시작 ─────────────────────────────────────────────────── */

async function boot() {
    let cfg;
    try {
        cfg = await fetch('/api/config').then(r => r.json());
    } catch (e) {
        setStatus('서버 정보를 읽지 못했습니다.', 'fail');
        return;
    }

    if (!cfg.kakaoJsKey) {
        setStatus('카카오 JavaScript 키가 없습니다. credentials/api_keys.properties 를 확인하세요.', 'fail');
        return;
    }

    loginId = cfg.loginId || '';

    $('meta').textContent = `${cfg.regionId} · 길 ${cfg.edgeCount.toLocaleString()}개`;
    document.title = `휠체어 이동경로 — ${cfg.regionId}`;

    // 안내 가능한 지역의 경계. 현재위치가 이 밖이면 지도를 옮겨도 보여줄 길이 없다.
    // 서버가 내려주는 이유는 region-id 를 바꾸면 값도 바뀌기 때문이다.
    serviceBounds = (cfg.bounds && cfg.bounds.length === 4) ? cfg.bounds : null;

    // 장소 검색에 services 라이브러리가 필요하다. 이게 빠지면 kakao.maps.services 가 undefined 다.
    const s = document.createElement('script');
    s.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${cfg.kakaoJsKey}`
        + `&libraries=services&autoload=false`;
    // 카카오 JS 키는 localhost:8080 에만 등록돼 있다. 다른 포트로 띄우면 여기로 온다.
    s.onerror = () => setStatus('지도를 불러오지 못했습니다. 8080 포트로 접속했는지 확인하세요.', 'fail');
    s.onload = () => kakao.maps.load(initMap);
    document.head.appendChild(s);
}

function initMap() {
    const c = initialCenter();
    map = new kakao.maps.Map($('map'), {
        center: new kakao.maps.LatLng(c[0], c[1]),
        level: 5
    });

    places = new kakao.maps.services.Places();

    /*
      지도가 '화면에 안 보이는 상태'에서 만들어지면(백그라운드 탭에서 연 경우 등)
      카카오가 컨테이너 크기를 0 으로 잡고, 그 뒤에 붙인 오버레이를 하나도 그리지 않는다.
      공사구간 점 57 개가 만들어졌는데 화면에는 0 개인 상태가 된다.

      크기가 잡히는 순간 relayout() 을 불러 다시 그리게 한다.
      창 크기를 바꿀 때도 같이 걸려서 반응형에도 도움이 된다.
    */
    if (window.ResizeObserver) {
        new ResizeObserver(() => map.relayout()).observe($('map'));
    }

    /*
      지도를 옮기거나 확대하면 화면 범위가 바뀌므로 그 안의 정류장을 다시 그린다.
      idle 은 '움직임이 멈췄을 때' 한 번 오지만, 확대·이동이 이어지면 여러 번 온다.
      그래서 250ms 를 더 기다렸다 한 번만 부른다.

      버스 탭이 아니면 loadStopsInView 가 스스로 빠져나가므로 여기서 가리지 않는다.
    */
    kakao.maps.event.addListener(map, 'idle', scheduleStopsInView);

    /*
      지도를 눌러 출발·도착을 찍는 기능은 여기에 두지 않는다.
      좌표를 직접 집는 일은 수정 화면(admin.html)의 몫이고,
      사용자는 장소 이름으로만 정한다. 지도 클릭이 살아 있으면
      지도를 옮기려다 잘못 눌러 출발지가 바뀌는 일이 생긴다.
    */

    setStatus('출발지와 도착지를 검색해 정하세요.');
    renderRecent();
    loadConstruction();
    loadReports();

    // 잰 기록에서 '평소 몇 분'을 만들어 둔다. 서버가 정류장별로 묶어 준다.
    reloadWalk();

    // [시작] 을 눌러둔 채 브라우저를 닫았다 다시 연 경우. 버튼 상태를 미리 맞춰둔다.
    renderTrack();

    /*
      어느 탭으로 열지. 기본은 도보이고, ?tab= 으로 지정할 수 있다.

      메인 화면(/main)의 [버스] 가 이 길로 들어온다 — 거기서 바로 버스 탭이 열려야
      '버스를 눌렀는데 도보 화면이 뜨는' 상태가 안 된다.

      ★ 아는 이름만 받는다. 모르는 값을 그대로 넘기면 어느 패널도 안 켜져
      화면이 통째로 빈 채로 뜬다 — 주소를 손으로 고친 사람에게 고장으로 보인다.
      여기서 한 번은 반드시 불러야 패널 표시가 탭 상태와 어긋나지 않는다.
    */
    const want = new URLSearchParams(location.search).get('tab');
    selectTab(['map', 'bus', 'taxi'].includes(want) ? want : 'map');

    // 사용자 동네로 지도를 옮긴다. 늦게 와도 되는 일이라 기다리지 않는다 —
    // 위치를 못 받아도 화면은 시청에 떠 있으면 그만이다.
    moveToMyArea();
}

/* ── 공사구간 ─────────────────────────────────────────────
   화면을 열자마자 그리고, 경로를 찾을 때마다 다시 받는다.

   다시 받는 이유: 관리자가 수정 화면에서 좌표·반경을 고치면 서버는 그 즉시 반영하는데,
   이미 열려 있는 이 화면의 점은 옛 자리에 남는다. 경로 계산은 매번 서버에 묻기 때문에
   이미 최신인데, 점만 뒤처지면 '막혔다는 곳을 지나가는 경로'로 보인다.

   점만 찍고 선은 그리지 않는다. 검증 화면(route.html)은 실제로 막히는 보도를
   선으로 같이 그리는데, 교차로에서 사방으로 뻗어 지도가 금세 어지러워진다.
   사용자 화면에서는 위치만 알면 충분하다. */

let zoneOverlays = [];

async function loadConstruction() {
    let zones;
    try {
        zones = await fetch('/api/overlay/construction').then(r => r.json());
    } catch (e) {
        return;   // 공사 표시는 곁들이라 실패해도 경로 찾기를 막지 않는다
    }

    // 다시 그리기 전에 걷어낸다. 안 그러면 부를 때마다 점이 겹쳐 쌓인다.
    zoneOverlays.forEach(o => o.setMap(null));
    zoneOverlays = [];

    zones.forEach(z => {
        const until = z.endDate ? `${z.endDate} 까지` : '종료일 미정';

        const el = document.createElement('div');
        el.className = 'zone';
        el.title = `${z.name}\n${until} 통행 불가\n공사구간: ${z.roadSegment}`;

        // clickable 을 켜지 않는다. 켜면 이 점이 지도 조작을 먹는다.
        zoneOverlays.push(new kakao.maps.CustomOverlay({
            position: new kakao.maps.LatLng(z.latitude, z.longitude),
            content: el, zIndex: 3, map: map
        }));
    });
}

/* ── 이용자 제보 ───────────────────────────────────────────
   공사구간과 같은 규칙이다. 점만 찍고 선은 그리지 않으며, 경로를 찾을 때마다 다시 받는다.
   같은 이유다 — 관리자가 위치를 고치거나 반려하면 서버는 즉시 반영하는데,
   열려 있는 이 화면의 점만 뒤처지면 '막혔다는 곳을 지나가는 경로'로 보인다.

   대기·공개만 그린다. 반려(관리자 오탐 판정)와 해소(장애물이 없어짐)는 경로 계산에서
   이미 빠진 것들이라, 지도에만 남겨두면 없는 장애물을 있다고 알리는 셈이 된다.

   제보자 아이디는 넣지 않는다. 여기는 누구나 보는 화면이라 '누가 올렸는지'가 드러날 이유가 없다.
   관리자 화면에는 그대로 나오므로 확정·반려 판단에는 지장이 없다. */

let reportOverlays = [];

/** 차단에 관여하는 상태. 이 둘이 아니면 사용자 화면에 그리지 않는다. */
const REP_LIVE = ['대기', '공개'];

const REP_CLASS = { '높음': 'high', '보통': 'mid', '낮음': 'low' };
const REP_LABEL = { '높음': '통행 불가', '보통': '통행 시 고려', '낮음': '통행 가능' };

async function loadReports() {
    let rows;
    try {
        rows = await fetch('/api/report').then(r => r.json());
    } catch (e) {
        return;   // 공사와 같다 — 곁들이라 실패해도 경로 찾기를 막지 않는다
    }

    // 다시 그리기 전에 걷어낸다. 안 그러면 부를 때마다 점이 겹쳐 쌓인다.
    reportOverlays.forEach(o => o.setMap(null));
    reportOverlays = [];

    rows.filter(r => REP_LIVE.includes(r.status)).forEach(r => {
        const el = document.createElement('div');
        el.className = 'rep ' + (REP_CLASS[r.severity] || 'low');
        el.title = `${REP_LABEL[r.severity] || r.severity} · ${r.obstacleType}`
            + (r.description ? `\n${r.description}` : '')
            + `\n${r.reportedAt} 이용자 제보`;

        reportOverlays.push(new kakao.maps.CustomOverlay({
            position: new kakao.maps.LatLng(r.latitude, r.longitude),
            content: el, zIndex: 3, map: map
        }));
    });
}

/* ── 장소 검색 ─────────────────────────────────────────────
   카카오는 자동완성 전용 API 를 열어두지 않았다. 같은 키워드 검색을 개수만 줄여 부르고
   이름만 뽑아 목록으로 보여준다. 결과가 같으므로 눈에 보이는 동작은 카카오와 같다. */

/**
 * 자동완성·검색결과에 얹을 <b>정류장 개수</b>.
 *
 * <p>많이 넣으면 안 된다. 같은 이름이 방향별로 둘씩 나오는 데다(보은군청입구 상·하행)
 * 자동완성은 10줄짜리라, 정류장이 위를 다 먹으면 정작 찾던 장소가 안 보인다.
 */
const BUS_STOP_HITS = 4;

/**
 * 이름으로 정류장을 찾아 <b>카카오 결과와 같은 모양</b>으로 바꾼다.
 *
 * <p><b>왜 필요한가</b>: 카카오 로컬 API 에는 버스정류장 카테고리가 아예 없다
 * (대중교통은 {@code SW8 지하철역} 하나뿐). 그래서 '보은여고' 를 쳐도 학교는 나오지만
 * <b>정류장은 한 건도 안 나온다</b>. 타려는 정류장을 이름으로 찾을 길이 없었다.
 *
 * <p>모양을 카카오에 맞추는 이유: 그러면 자동완성·검색결과·마커·핀 코드를 하나도
 * 손대지 않아도 된다. 우리 것임을 알아야 하는 자리에서만 {@code _stop} 을 본다.
 *
 * <p>실패하면 빈 목록이다. 정류장을 못 받았다고 장소 검색까지 막을 이유는 없다.
 */
async function busStopPlaces(query, limit) {
    try {
        const c = map.getCenter();
        const url = `/api/bus/stops-find?q=${encodeURIComponent(query)}`
            + `&lat=${c.getLat()}&lng=${c.getLng()}&limit=${limit}`;

        const res = await fetch(url);
        if (!res.ok) return [];              // 503(제공자 없음) 포함. 조용히 넘어간다

        const data = await res.json();
        return (data.stops || []).map(s => ({
            place_name: s.stopName,
            category_group_name: '버스정류장',
            // 같은 이름이 방향별로 둘이라 거리가 유일한 구분점이다. 이게 없으면 못 고른다.
            road_address_name: `지도 중심에서 ${s.distanceM.toLocaleString()}m`,
            x: String(s.longitude),
            y: String(s.latitude),
            // 카카오 결과에는 없는 칸. 이게 있으면 우리 정류장이다.
            _stop: s
        }));
    } catch (e) {
        return [];
    }
}

function keywordSearch(query, size, page, done) {
    /*
      ★ 정류장은 <b>첫 쪽에만</b> 얹는다. [더보기] 는 카카오의 다음 쪽을 받는 것이라
      거기에 또 끼우면 같은 정류장이 쪽마다 반복된다.

      카카오가 0건이어도 정류장은 나와야 한다 — 정류장 이름으로 검색하면
      카카오는 늘 0건이라, 그때 빈손으로 돌아가면 이 기능이 아무 일도 안 한 것이 된다.
    */
    const finish = (data, pg) => {
        if ((page || 1) > 1) { done(data, pg); return; }
        busStopPlaces(query, BUS_STOP_HITS).then(stops => done(stops.concat(data), pg));
    };

    const opts = {
        size: size,
        page: page || 1,
        // 지도 중심 쪽을 먼저 준다. 우리 그래프가 중구뿐이라 가까운 곳이 먼저 나와야 쓸모 있다.
        location: map.getCenter(),
        radius: 20000,
        sort: kakao.maps.services.SortBy.ACCURACY
    };

    places.keywordSearch(query, (data, status, pg) => {
        if (status === kakao.maps.services.Status.OK) {
            finish(data, pg);
            return;
        }
        // 반경 밖이면 아무것도 안 나온다. 그때는 범위를 풀고 한 번 더 본다.
        if (status === kakao.maps.services.Status.ZERO_RESULT && opts.location) {
            places.keywordSearch(query,
                (d2, s2, p2) => finish(s2 === kakao.maps.services.Status.OK ? d2 : [], p2),
                { size: size, page: page || 1 });
            return;
        }
        finish([], null);
    }, opts);
}

/* ── 자동완성 ─────────────────────────────────────────────── */

const debounced = {};

function bindSuggest(inputId, listId, field) {
    const input = $(inputId);
    const list = $(listId);

    input.addEventListener('input', () => {
        const q = input.value.trim();

        toggleClear(field, !!q);

        clearTimeout(debounced[inputId]);
        if (q.length < 1) { hideSuggest(list); return; }

        debounced[inputId] = setTimeout(() => {
            if (!places) return;
            keywordSearch(q, SUGGEST_SIZE, 1, (data) => {
                if (input.value.trim() !== q) return;   // 그 사이에 더 입력했으면 버린다
                renderSuggest(list, data, q, field, input);
            });
        }, 250);
    });

    input.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') { hideSuggest(list); return; }

        if (e.key === 'Enter') {
            e.preventDefault();
            const cursor = list.querySelector('.is-cursor');
            if (cursor && !list.hidden) { cursor.click(); return; }

            const q = input.value.trim();
            if (q) { hideSuggest(list); runSearch(q, field); }
            return;
        }

        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            moveCursor(list, e.key === 'ArrowDown' ? 1 : -1);
        }
    });

    // 바깥을 누르면 닫는다. 목록 안의 클릭이 먼저 처리되도록 mousedown 이 아니라 click 이다.
    document.addEventListener('click', (e) => {
        if (!input.parentElement.contains(e.target)) hideSuggest(list);
    });
}

function renderSuggest(list, data, query, field, input) {
    list.innerHTML = '';

    if (!data.length) { hideSuggest(list); return; }

    data.forEach(p => {
        const li = document.createElement('li');
        const b = document.createElement('button');
        b.type = 'button';
        b.innerHTML = highlight(p.place_name, query);

        const sub = document.createElement('span');
        sub.className = 'sub';
        /*
          정류장은 종류를 같이 적는다. 이름만 보면 '보은여고' 가 학교인지 정류장인지
          구분이 안 되는데, 그 둘은 좌표가 1km 넘게 떨어져 있다.
          카카오 결과는 지금까지처럼 주소만 적는다 — 굳이 바꿀 이유가 없다.
        */
        sub.textContent = p._stop
            ? `버스정류장 · ${p.road_address_name}`
            : (p.road_address_name || p.address_name || '');
        b.appendChild(sub);

        /*
          카카오는 자동완성에서 고르면 두 가지를 한꺼번에 한다.
            ① 고른 곳을 그 칸(출발/도착)에 넣는다
            ② 검색결과는 '고른 이름' 이 아니라 '입력한 말' 로 낸다
          그래서 '종각역' 을 치고 '종각역 1호선' 을 골라도 결과는 종각역 81건 그대로고,
          고른 것만 A 에 '출발' 배지가 붙는다. 같게 맞춰뒀다.
        */
        b.addEventListener('click', () => {
            input.value = p.place_name;
            toggleClear(field, true);
            hideSuggest(list);

            const lat = Number(p.y), lng = Number(p.x);
            if (field) setPlace(field, { name: p.place_name, lat, lng });

            // 버스 탭에서 정류장을 골랐으면 그게 곧 '타려는 정류장'이다.
            // 이름으로 찾아놓고 목록에서 또 눌러야 하면 찾은 뜻이 없다.
            if (p._stop && activeTab === 'bus') pickBusStop(p._stop);

            // 출발·도착이 다 찼으면 검색결과를 띄우지 않고 곧장 경로로 간다.
            // 띄웠다가는 늦게 도착한 검색 응답이 경로 화면을 덮는다.
            if (field && picked.start && picked.end) { maybeRoute(); return; }

            runSearch(query, field, new kakao.maps.LatLng(lat, lng));
        });

        li.appendChild(b);
        list.appendChild(li);
    });

    list.hidden = false;
}

/** 입력한 글자만 색을 준다. 카카오와 같은 표시다. */
function highlight(text, query) {
    const i = text.toLowerCase().indexOf(query.toLowerCase());
    if (i < 0) return escapeHtml(text);

    return escapeHtml(text.slice(0, i))
        + '<em class="hit">' + escapeHtml(text.slice(i, i + query.length)) + '</em>'
        + escapeHtml(text.slice(i + query.length));
}

function escapeHtml(s) {
    return s.replace(/[&<>"']/g, c =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function hideSuggest(list) {
    list.hidden = true;
    list.innerHTML = '';
}

function moveCursor(list, delta) {
    if (list.hidden) return;

    const items = [...list.querySelectorAll('button')];
    if (!items.length) return;

    const now = items.findIndex(b => b.classList.contains('is-cursor'));
    const next = (now + delta + items.length + (now < 0 ? 1 : 0)) % items.length;

    items.forEach(b => b.classList.remove('is-cursor'));
    items[next].classList.add('is-cursor');
    items[next].scrollIntoView({ block: 'nearest' });
}

/* ── 검색 결과 목록 ────────────────────────────────────────── */

/**
 * focus 를 주면 그 지점으로 지도를 옮기고, 없으면 결과 전체가 보이게 맞춘다.
 *
 * <p>카카오 응답은 부른 순서대로 오지 않는다. 순번을 붙여 <b>마지막에 부른 것만</b>
 * 화면에 반영한다. 이게 없으면 늦게 온 응답이 이미 그려진 경로 화면을 덮는다.
 */
let searchSeq = 0;

function runSearch(query, field, focus) {
    setStatus('검색하는 중…');

    const seq = ++searchSeq;

    keywordSearch(query, RESULT_SIZE, 1, (data, pg) => {
        if (seq !== searchSeq) return;   // 그 사이 다른 검색이 들어왔다
        pagination = pg;
        searchState = { query, field, items: data.slice() };

        if (!data.length) {
            setStatus(`'${query}' 검색 결과가 없습니다.`, 'fail');
            $('pane-search').hidden = true;
            clearResultMarkers();
            return;
        }

        setStatus(field
            ? `원하는 곳을 고르면 ${field === 'start' ? '출발지' : '도착지'}로 정합니다.`
            : '원하는 곳을 고르면 지도가 이동합니다.');

        renderResults(focus);
        $('pane-route').hidden = true;
        $('pane-recent').hidden = true;
    });
}

function renderResults(focus) {
    const { query, field, items } = searchState;

    $('sr-query').textContent = query;
    $('sr-count').textContent = pagination
        ? `장소 ${pagination.totalCount.toLocaleString()}` : `장소 ${items.length}`;

    const ul = $('place-list');
    ul.innerHTML = '';

    // 이미 출발·도착으로 잡힌 것이 목록에 있으면 그 마커를 크게 그린다.
    let pickedIdx = -1;
    items.forEach((p, i) => {
        ul.appendChild(placeRow(p, i, field));
        if (pickedIdx < 0 && roleOf(p)) pickedIdx = i;
    });

    $('pane-search').hidden = false;
    $('btn-more').hidden = !(pagination && pagination.hasNextPage);

    drawResultMarkers(items, field, pickedIdx);

    if (focus) {
        map.setCenter(focus);
        if (map.getLevel() > 5) map.setLevel(4);
    } else {
        fitToResults(items);
    }
}

function placeRow(p, i, field) {
    const li = document.createElement('li');

    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'place-item';
    b.dataset.idx = String(i);

    const name = document.createElement('div');
    name.className = 'place-name';
    name.innerHTML = `<span class="mark">${letter(i)}</span>${escapeHtml(p.place_name)}`;
    if (p.category_group_name) {
        name.innerHTML += `<span class="cat">${escapeHtml(p.category_group_name)}</span>`;
    }
    b.appendChild(name);

    if (p.road_address_name) {
        const road = document.createElement('div');
        road.className = 'place-addr';
        road.textContent = p.road_address_name;
        b.appendChild(road);
    }
    if (p.address_name) {
        const jibun = document.createElement('div');
        jibun.className = 'place-jibun';
        jibun.textContent = `(지번) ${p.address_name}`;
        b.appendChild(jibun);
    }
    if (p.phone) {
        const tel = document.createElement('div');
        tel.className = 'place-tel';
        tel.textContent = p.phone;
        b.appendChild(tel);
    }

    // 이미 출발·도착으로 잡힌 곳이면 배지를 단다.
    const role = roleOf(p);
    if (role) {
        b.classList.add('is-picked');
        const badge = document.createElement('span');
        badge.className = 'place-badge' + (role === 'end' ? ' is-end' : '');
        badge.textContent = role === 'end' ? '도착' : '출발';
        b.appendChild(badge);
    }

    b.addEventListener('click', () => choosePlace(p, i, field));

    li.appendChild(b);
    return li;
}

function letter(i) {
    // A~Z 를 넘어가면 그냥 번호로 준다. 한 쪽에 15개라 실제로는 오지 않는다.
    return i < 26 ? String.fromCharCode(65 + i) : String(i + 1);
}

function roleOf(p) {
    const lat = Number(p.y), lng = Number(p.x);
    if (picked.start && same(picked.start, lat, lng)) return 'start';
    if (picked.end && same(picked.end, lat, lng)) return 'end';
    return null;
}

function same(a, lat, lng) {
    return Math.abs(a.lat - lat) < 1e-7 && Math.abs(a.lng - lng) < 1e-7;
}

/* 결과 하나를 고르면 지도를 그리로 옮긴다. 어느 칸에서 검색했는지에 따라 출발·도착이 정해진다. */
function choosePlace(p, i, field) {
    const lat = Number(p.y), lng = Number(p.x);
    const latLng = new kakao.maps.LatLng(lat, lng);

    map.setCenter(latLng);
    if (map.getLevel() > 5) map.setLevel(4);

    if (!field) {                      // 지도 위 검색바에서 온 검색이면 이동만 한다
        setStatus(`${p.place_name} 으로 이동했습니다.`);
        drawResultMarkers(searchState.items, null, i);
        return;
    }

    setPlace(field, { name: p.place_name, lat, lng });

    // 자동완성과 같다 — 버스 탭에서 고른 정류장은 곧 '타려는 정류장'이다.
    if (p._stop && activeTab === 'bus') pickBusStop(p._stop);

    // 둘 다 찼으면 목록을 다시 그리지 않고 경로로 넘어간다(위 자동완성과 같은 이유).
    if (picked.start && picked.end) { maybeRoute(); return; }

    renderResults(latLng);
}

function setPlace(field, place) {
    picked[field] = place;
    $(field === 'start' ? 'in-start' : 'in-end').value = place.name;
    toggleClear(field, true);

    const pin = field === 'start' ? startPin : endPin;
    if (pin) pin.setMap(null);

    const made = placePin(new kakao.maps.LatLng(place.lat, place.lng),
        field === 'start' ? '출발' : '도착',
        field === 'start' ? '#2e7d32' : '#c62828');

    if (field === 'start') startPin = made; else endPin = made;

    // 도착지가 바뀌면 버스 탭의 패널 표시도 따라간다.
    // (전에는 '도착지가 곧 정류장'이었지만 이제 둘은 별개다 — busStop 참고)
    if (field === 'end' && activeTab === 'bus') {
        renderPanes();
    }

    /*
      ★ 출발지가 채워지면 정류장까지의 거리·점선을 다시 만든다.

      정류장을 먼저 고르고 출발지를 나중에 정하는 순서가 흔한데(지도를 보다가
      정류장을 누르고, 그다음 집을 넣는다), 다시 재지 않으면 busStopMeters 가 0 으로
      남아 <b>점선도 안 그려지고 기록에 거리도 안 붙는다.</b>
      measureToStop 안에서 renderTrack 도 부르므로 안내 문구도 같이 맞춰진다.
    */
    if (field === 'start' && activeTab === 'bus') {
        if (busStop) measureToStop(); else renderTrack();

        // 재는 구간의 '출발' 줄도 같이 바꾼다. measureToStop 은 정류장이 있어야 돌아서,
        // 정류장을 아직 안 고른 상태에서는 여기서 부르지 않으면 옛 문구가 남는다.
        renderSeg();

        // 탈 수 있는 정류장 후보도 새 출발지로 다시 받는다.
        loadBoardingStops();
    }
}

function loadMore() {
    if (!pagination || !pagination.hasNextPage) return;

    // 카카오 pagination 은 gotoNext() 를 부르면 처음 넘긴 콜백을 다시 부른다.
    // 우리는 콜백을 직접 들고 있지 않으므로 페이지 번호로 다시 검색한다.
    const next = pagination.current + 1;

    keywordSearch(searchState.query, RESULT_SIZE, next, (data, pg) => {
        pagination = pg;
        searchState.items = searchState.items.concat(data);
        renderResults(map.getCenter());   // 더 보기로 화면이 튀지 않게 지금 자리를 지킨다
    });
}

/* ── 지도 위 마커 ─────────────────────────────────────────── */

function clearResultMarkers() {
    resultMarkers.forEach(m => m.setMap(null));
    resultMarkers = [];
}

function drawResultMarkers(items, field, pickedIdx) {
    clearResultMarkers();

    items.forEach((p, i) => {
        const el = document.createElement('div');
        el.className = 'mk' + (i === pickedIdx ? ' is-picked' : '');
        el.innerHTML = `<span>${letter(i)}</span>`;
        el.title = p.place_name;

        el.addEventListener('click', () => choosePlace(p, i, field));

        resultMarkers.push(new kakao.maps.CustomOverlay({
            position: new kakao.maps.LatLng(Number(p.y), Number(p.x)),
            content: el, yAnchor: 1, zIndex: 6, clickable: true, map: map
        }));
    });
}

function fitToResults(items) {
    if (!items.length) return;

    const bounds = new kakao.maps.LatLngBounds();
    items.forEach(p => bounds.extend(new kakao.maps.LatLng(Number(p.y), Number(p.x))));
    map.setBounds(bounds);
}

/* 기본 Marker 는 클릭을 자기가 먹어서 지도 클릭을 막는다. 오버레이는 통과시킨다. */
function placePin(latLng, text, color) {
    const el = document.createElement('div');
    el.className = 'pin';
    el.style.background = color;
    el.textContent = text;

    return new kakao.maps.CustomOverlay({
        position: latLng, content: el, yAnchor: 1.6, zIndex: 7, map: map
    });
}

/* ── 경로 ─────────────────────────────────────────────────── */

function maybeRoute() {
    if (!picked.start || !picked.end) return;
    searchRoute();
}

async function searchRoute() {
    setStatus('경로를 찾는 중…');

    // 아직 돌아오지 않은 장소 검색이 있으면 버린다. 경로 화면이 우선이다.
    searchSeq++;

    // 관리자가 고친 공사구간·제보를 같이 최신으로 맞춘다. 기다리지 않는다 —
    // 이게 늦어져도 경로 안내는 나가야 한다.
    loadConstruction();
    loadReports();

    const url = `/api/route?startLat=${picked.start.lat}&startLng=${picked.start.lng}`
        + `&endLat=${picked.end.lat}&endLng=${picked.end.lng}`;

    let res;
    try {
        res = await fetch(url).then(r => r.json());
    } catch (e) {
        setStatus('경로를 받지 못했습니다: ' + e.message, 'fail');
        return;
    }

    if (routeLine) { routeLine.setMap(null); routeLine = null; }

    if (res.resultStatus !== '성공') {
        // 사용자에게는 원인보다 '왜 못 갔는지'가 중요하다.
        setStatus(res.resultStatus === '경로없음'
            ? '갈 수 있는 길을 찾지 못했습니다. 계단·공사 구간을 피하다 보면 이어지지 않는 곳이 있습니다.'
            : `경로를 계산하지 못했습니다. ${res.message ?? ''}`, 'fail');
        lastMeters = 0;
        renderPanes();
        return;
    }

    const points = res.path.map(p => new kakao.maps.LatLng(p[0], p[1]));

    routeLine = new kakao.maps.Polyline({
        path: points, strokeWeight: 7, strokeColor: '#1565c0',
        strokeOpacity: .95, strokeStyle: 'solid', zIndex: 4, map: map
    });

    showRoute(res);

    // 결과 마커는 경로를 가리므로 치운다.
    clearResultMarkers();
    $('pane-search').hidden = true;

    const bounds = new kakao.maps.LatLngBounds();
    points.forEach(p => bounds.extend(p));
    map.setBounds(bounds);
}

function showRoute(res) {
    const meters = Math.round(res.distanceM);

    $('r-dist').textContent = meters >= 1000
        ? `${(meters / 1000).toFixed(1)}km`
        : `${meters.toLocaleString()}m`;

    // 거리를 먼저 넣어야 renderPanes 가 '경로 있음'으로 판단한다.
    // 버스 탭에 있는 상태로 경로를 찾으면 그쪽 계산이 바로 채워진다.
    lastMeters = meters;
    renderRouteTime();
    renderPanes();

    setStatus('경로를 찾았습니다.', 'ok');
    pushRecent(meters, Math.max(1, Math.ceil(meters / WALK_M_PER_MIN)));
}

/** 도보 기준 시간(분). 4km/h 고정값으로, 누구에게나 같은 값이다. */
const baseMinutes = (meters) => Math.max(1, Math.ceil(meters / 66.7));

/**
 * 경로 시간. <b>도보 기준과 그 사람 기준을 같이 보여준다.</b>
 *
 * <p>조용히 개인 값으로 바꿔치기하지 않는 이유: 같은 1.2km 인데 어제 18분이던 것이
 * 오늘 27분이 되면 사용자는 앱이 고장 났다고 본다. 나란히 두면 "내가 도보보다 1.5배구나"를
 * 스스로 알게 되고, 값이 이상할 때 눈치챌 수 있다.
 *
 * <p><b>올림한다.</b> 8.2분을 8분으로 알려주면 매번 조금씩 늦는다.
 */
function renderRouteTime() {
    if (lastMeters <= 0) {
        return;
    }
    const base = baseMinutes(lastMeters);
    const mine = Math.max(1, Math.ceil(lastMeters / WALK_M_PER_MIN));
    const ratio = speedInfo.walkRatio;

    $('r-time').textContent = `${mine}분`;

    // 배수를 아직 모르면(기록 부족) 서버가 66.7 을 그대로 주므로 두 값이 같다.
    if (ratio == null || mine === base) {
        $('r-note').textContent = '계단과 공사 구간을 피한 경로입니다.';
        return;
    }

    /*
      ★ 더 빠른 경우도 있다. 전동휠체어는 4km/h 를 넘는다 —
      '항상 느리다' 고 가정하면 그 사용자에게는 매번 틀린 말을 하게 된다.
    */
    const diff = mine - base;
    const spread = (speedInfo.ratioLow != null && speedInfo.ratioHigh != null)
        ? ` (${speedInfo.ratioLow}~${speedInfo.ratioHigh})` : '';

    $('r-note').innerHTML = '계단과 공사 구간을 피한 경로입니다.<br>'
        + `도보 기준 ${base}분 · <b>${diff > 0 ? `${diff}분 더` : `${-diff}분 덜`} 걸립니다</b>`
        + ` — 잰 기록 ${speedInfo.ratioCount}건 기준 도보의 ${ratio}배${spread}`;
}

/* ── 이동수단 탭 ───────────────────────────────────────────
   지도 / 버스 / 택시는 같은 출발·도착에 대한 '이동수단' 탭이다.
   그래서 페이지를 나누지 않고 패널만 바꾼다 — 출발·도착 입력과 지도는 그대로 살아 있어야 한다.

   경로 탐색은 어느 탭에 있든 똑같이 돈다. 버스 탭도 거리를 알아야 나갈 시각을 낼 수 있다. */

let activeTab = 'map';

function selectTab(name) {
    activeTab = name;

    document.querySelectorAll('.rail-tab[data-tab]').forEach(t => {
        if (['map', 'bus', 'taxi'].includes(t.dataset.tab)) {
            t.classList.toggle('is-active', t.dataset.tab === name);
        }
    });

    renderPanes();
}

/**
 * 지금 탭에 맞는 패널만 남긴다.
 *
 * <p>경로 결과·최근경로는 지도 탭의 것이다. 버스 탭에서 경로 요약까지 같이 보이면
 * 무엇이 이 탭의 내용인지 흐려진다.
 */
function renderPanes() {
    const hasRoute = lastMeters > 0;
    const bus = activeTab === 'bus';
    const taxi = activeTab === 'taxi';

    /*
      ★ 택시 탭은 출발·도착 칸을 통째로 감춘다.

      다른 탭과 달리 이 탭은 '어디에서 어디로'와 무관하다 — 우리가 차를 부르는 것이 아니라
      어느 기관에 전화하는지를 알려줄 뿐이라, 출발지를 채워도 화면이 달라지지 않는다.
      남겨두면 사용자는 그것부터 채워야 하는 줄 알고 검색하다가 아무 일도 안 일어나는 것을 본다.

      상태 줄('출발지와 도착지를 검색해 정하세요')도 같이 감춘다. 칸이 없는데 그 안내만
      남으면 어디를 검색하라는 말인지 알 수 없다.
    */
    $('io').hidden     = taxi;
    $('status').hidden = taxi;

    /*
      ★ 도착지 칸은 어느 탭에서나 '도착지' 하나의 뜻이다.

      전에는 버스 탭에서만 '타려는 정류장'으로 쓰고 바로가기·교체 버튼을 감췄다.
      그때는 버스 탭이 하는 일이 '집에서 정류장까지'뿐이라 말이 됐는데,
      복합 경로가 들어오면 버스 탭에서도 도착지는 진짜 목적지여야 한다.
      타려는 정류장은 오른쪽 정류장 칸에서 고르고 busStop 이 따로 들고 있다.
    */
    $('in-end').placeholder = '도착지';
    $('shortcuts').hidden = false;
    $('btn-swap').hidden  = false;

    /*
      ★ 정류장 칸은 버스 탭에서만 선다.

      다른 탭에서 남겨두면 지도 폭만 먹는다 — 도보 탭에서 정류장 조회와 스톱워치는
      할 일이 없다. hidden 을 쓰는 이유는 접힘 상태(is-collapsed)와 따로 놀아야 하기
      때문이다. 접어둔 채 탭을 옮겼다가 돌아오면 접힌 그대로여야 한다.
    */
    $('side').hidden = !bus;

    /*
      경로 요약과 최근경로는 이제 <b>버스 탭에서도 보인다</b>.
      도착지 칸이 두 탭에서 같은 뜻이 됐으니 결과도 같이 따라와야 한다 —
      버스 탭에서 목적지를 정했는데 경로가 안 보이면 어디로 가는 중인지 알 수 없다.
    */
    $('pane-route').hidden  = !hasRoute;
    $('pane-recent').hidden = taxi;
    $('pane-bus').hidden    = !bus;
    $('pane-taxi').hidden   = !(activeTab === 'taxi');

    // 버스 탭은 경로와 무관하게 돈다 — 스톱워치가 재는 것은 집에서 정류장까지 한 구간이라
    // 출발·도착을 검색하지 않아도 쓸 수 있어야 한다.
    if (activeTab === 'bus') {
        renderTrack();

        /*
          재는 구간을 먼저 그린다. 여기서 안 그리면 탭을 처음 열었을 때
          '—' 가 남아, 아직 못 정한 것인지 값이 없는 것인지 알 수 없다.
          (아래 reloadWalk 는 비로그인이면 일찍 돌아가므로 거기에 기댈 수 없다)
        */
        renderSeg();
        loadBoardingStops();

        /*
          ★ 복합 경로. 출발지와 도착지가 둘 다 정해졌을 때만 뜬다 —
          '어디로 가는지' 를 모르면 어디서 내릴지도 정할 수 없다.
          같은 구간이면 다시 받지 않으므로(planFor) 탭을 오갈 때마다 호출되지 않는다.
        */
        loadPlan();

        /*
          ★ 지도 마커를 다시 찍는다.
          지도 탭으로 나갈 때 지우므로(아래 else), 돌아와서 다시 그리지 않으면
          패널 목록은 8곳인데 지도는 비어 있는 상태가 된다. 실제로 그랬다.
        */
        drawBusStopMarkers();
        loadStopsInView();      // 화면 범위 안의 정류장도 채운다

        /*
          ★ 정류장까지의 점선도 다시 그린다. 탭을 떠날 때 치우므로 여기서 안 되살리면
          패널에는 정류장이 골라져 있는데 지도에는 아무것도 없는 상태가 된다 —
          마커에서 똑같은 일을 이미 겪었다(9장).
        */
        if (busStop) {
            measureToStop();
        }

        // 정류장이 바뀌었을 수 있다. 그 정류장 묶음의 값으로 다시 읽는다.
        // 속도·기록·역산 표시는 응답이 온 뒤 reloadWalk 안에서 같이 그린다.
        reloadWalk();

        /*
          ★ 정류장을 스스로 찾지 않는다. [이 근처 정류장 찾기] 를 눌러야 찾는다.

          자동으로 띄우면 사용자가 부르지도 않은 목록이 도착지 검색 결과 아래에 끼어들어,
          지금 뭘 보고 있는 화면인지 흐려진다. 기준 좌표도 '지금 지도 한가운데' 라
          사용자가 의도한 자리가 아닐 때가 많다.

          곁들여 호출도 아낀다 — 탭만 열어보고 마는 경우에는 한 번도 안 부른다.
        */
    } else {
        // 다른 탭으로 옮기면 숫자가 안 보이므로 1초마다 도는 타이머를 멈춘다.
        // 진행 상태는 localStorage 에 있으니 돌아오면 이어진다.
        if (swTimer) {
            clearInterval(swTimer);
            swTimer = null;
        }
        // 도착정보도 마찬가지다. 안 보는 화면 때문에 45초마다 API 를 부를 이유가 없다.
        stopBusLive();
        // 정류장 마커는 버스 탭의 것이다. 지도 탭에서 경로를 볼 때 남아 있으면 어지럽다.
        mapStops = [];
        clearBusStopMarkers();
        // 정류장까지의 점선도 같은 이유로 치운다. 버스 탭으로 돌아오면 다시 그린다.
        clearStopLine();
        // 복합 경로 선도 마찬가지다. 지도 탭에서 도보 경로를 볼 때 버스 선이 남아 있으면
        // 어느 것이 지금 보는 안인지 알 수 없다.
        clearPlanLines();
        renderPlan();
    }

    if (activeTab === 'taxi') {
        renderTaxi();
    }
}

/* ── 택시 탭 — 장애인콜택시 안내 ────────────────────────────
   ★ 우리가 차를 부르지는 못한다. 장애인콜택시는 배차가 기관 시스템 안에서만 돌고,
   버스 도착정보와 달리 외부 호출·실시간 API 가 공개돼 있지 않다.
   그래서 이 탭이 할 수 있는 일은 '어디에 어떻게 연락하는지'를 갈라주는 것뿐이다.

   그런데 그 갈래가 실제로 값이 나간다 — 기관마다 대상과 가입 방법이 달라서,
   잘못 고르면 될 일을 안 되는 방식으로 하게 된다.

   문구는 전부 이 표에 모아둔다. 지역이 늘면 여기에 항목을 더한다. */

const TAXI_ORGS = {
    dtis: {
        name: '교통약자 이동편의 정보관리 시스템 (DTIS)',
        // 2025-09-29 시범 시작. 대전·세종·충북 11개 시·군(보은 포함).
        note: '대전·세종·충북 통합예약. 한 번 가입하면 11개 시·군에서 지역별 재가입 없이 씁니다. '
            + '중증보행장애인이 대상이고, 가입이 온라인으로 끝납니다.',
        telLabel: '통합예약 (원넘버)',
        tel: '1599-8881',

        // 메뉴 이름은 2026-08-19 에 사이트에서 직접 읽은 그대로다. 바뀌면 여기만 고친다.
        site: {
            url: 'https://dtis.kotsa.or.kr',
            routes: [
                {
                    goal: '즉시배차 신청',
                    // 메뉴가 '실시간 접수' 하나뿐이다 — 예약 메뉴가 아예 없다.
                    note: '당일 것만 됩니다. 1~2일 뒤 예약은 전화(1599-8881)로만 받습니다.',
                    steps: ['우측 상단 [로그인]', '[특별교통수단 예약] → [실시간 접수]']
                },
                {
                    goal: '이용내역 확인',
                    steps: ['[마이메뉴] → [이용내역]']
                },
                {
                    goal: '통합회원 신청',
                    steps: ['우측 상단 [통합회원 신청]'],
                    /*
                      ★ 실제 신청 폼에서 확인한 함정이다(2026-08-19).
                      정부24 에서 장애인증명서를 받으면 PDF 로 나오는데, 이 폼은 이미지만 받는다.
                      어디에도 안내가 없어서 여기서 막히는 사람이 생긴다.
                    */
                    warn: '첨부는 사진 파일만 됩니다 — .jpg · .jpeg · .png · .gif, 12MB 이하. '
                        + '정부24 에서 받은 PDF 는 그대로 올라가지 않으니 이미지로 바꿔야 합니다.',
                    fields: [
                        {
                            k: '신청 대상',
                            v: '중증보행장애인. 거주지가 시범지역(대전·세종·충북)이 아니어도 신청됩니다.'
                        },
                        {
                            k: '필요 자료',
                            // 충북과 달리 자격이 갈리지 않는다 — 한 묶음뿐이라 고르는 버튼이 안 뜬다.
                            docs: [
                                {
                                    who: '중증보행장애인',
                                    items: [
                                        {
                                            name: '장애인증명서',
                                            how: 'link',
                                            linkText: '정부24에서 발급',
                                            url: 'https://www.gov.kr/mw/AA020InfoCappView.do?CappBizCD=14600000273'
                                        }
                                    ]
                                }
                            ]
                        },
                        {
                            k: '신청방법',
                            v: '누리집·앱에서 바로 (센터 방문·팩스도 가능)'
                        }
                    ],
                    // 폼에서 장애 구분 칸이 '먼저 심사지역을 선택하세요'로 잠겨 있다.
                    note: '심사지역을 먼저 골라야 장애 구분 칸이 열립니다. '
                        + '거주 지역이 시범지역이 아니면 원하는 지역을 고르면 됩니다.'
                }
            ]
        }
    },
    cb: {
        name: '충청북도광역이동지원센터',
        // 대상이 DTIS 보다 넓다 — 노인·국가유공자·임산부까지 받는다.
        note: '충북 관내. 노인(요양등급 1~3)·국가유공자·임산부도 대상입니다. '
            + '전화로 먼저 정보등록을 한 뒤, 서류는 방문·우편·팩스로 냅니다.',
        telLabel: '대표번호',
        tel: '1533-0220',

        /*
          사이트에 들어간 다음 어디를 눌러야 하는지.

          주소만 주면 반은 만 것이다 — 메뉴가 5갈래인데 정작 접수는 로그인 뒤에 열리고,
          [회원가입] 은 눌러도 가입이 안 된다(안내문만 나온다). 그걸 모르면 그 앞에서 막힌다.

          메뉴 이름은 2026-08-19 에 사이트에서 직접 읽은 그대로다. 바뀌면 여기만 고친다.
        */
        site: {
            url: 'https://chungbuk-th-back.essetel.com',
            routes: [
                {
                    goal: '즉시콜 접수',
                    steps: ['우측 상단 [로그인]', '상단 [즉시콜접수] → [접수하기]'],
                    // 예약배차는 이 사이트에서 안 된다. 전화만 된다.
                    note: '당일 이용만 됩니다. 1~2일 뒤 예약은 전화로만 받습니다.'
                },
                {
                    goal: '접수내역 확인·취소',
                    steps: ['상단 [즉시콜접수] → [접수내역 및 취소]']
                },
                {
                    goal: '회원가입',
                    steps: ['우측 상단 [회원가입]'],
                    // ★ 실제로 확인한 것: 이 화면은 절차 안내문이지 가입 폼이 아니다.
                    warn: '여기서 가입이 되지는 않습니다. 절차 안내만 나옵니다 — '
                        + '신규는 전화(1533-0220)로 정보등록을 먼저 해야 합니다.',
                    /*
                      그 안내문에 적힌 내용을 옮겨 적는다. 항목을 갈라 두는 이유는
                      챙겨야 할 것이 성격이 다른 셋이기 때문이다 —
                      누가 신청할 수 있나 / 무엇을 준비하나 / 어떻게 내나.
                      한 문단으로 이어 쓰면 무엇이 빠졌는지 눈으로 셀 수 없다.
                    */
                    fields: [
                        {
                            k: '신청 가능자',
                            v: '본인, 법정대리인, 활동보조인, 보호자, 사회복지시설의 장'
                        },
                        {
                            k: '필요 자료',
                            /*
                              ★ 자격을 먼저 고르게 하고, 고른 사람 것만 보여준다.

                              셋을 한꺼번에 늘어놓으면 자기와 상관없는 서류까지 읽게 되고,
                              '복지카드도 내야 하나' 하고 헷갈린다. 실제로는 셋 중 하나만 해당한다.

                              체크는 남는다(localStorage). 서류 챙기는 일은 하루에 끝나지 않아서,
                              창을 닫으면 지워지는 체크는 안 하느니만 못하다.

                              how 는 '그럼 어떻게 구하나'다. 셋뿐이다 —
                                link   온라인으로 발급된다        (2026-08-19 확인: 장기요양인정서만 해당)
                                center 센터에서 받아 작성한다     (사이트에 양식이 없다. 뒤져서 확인함)
                                own    이미 가진 실물을 사본·사진으로
                            */
                            docs: [
                                {
                                    who: '장애인',
                                    items: [
                                        { name: '심사신청서', how: 'center' },
                                        { name: '개인정보 이용 동의서', how: 'center' },
                                        { name: '복지카드', how: 'own' }
                                    ]
                                },
                                {
                                    who: '국가유공자',
                                    items: [
                                        { name: '심사신청서', how: 'center' },
                                        { name: '개인정보 이용 동의서', how: 'center' },
                                        { name: '국가유공자증', how: 'own' }
                                    ]
                                },
                                {
                                    who: '만 65세 이상 노인',
                                    items: [
                                        { name: '심사신청서', how: 'center' },
                                        { name: '개인정보 이용 동의서', how: 'center' },
                                        {
                                            name: '장기요양인정서 (1~3급)',
                                            how: 'link',
                                            linkText: '정부24에서 발급',
                                            url: 'https://www.gov.kr/portal/service/serviceInfo/B55092800030'
                                        }
                                    ]
                                }
                            ]
                        },
                        {
                            k: '신청방법',
                            v: '직접방문, 우편, 팩스 등'
                        }
                    ]
                }
            ]
        }
    }
};

let taxiOrg = 'dtis';

/**
 * 고른 기관의 안내를 그린다.
 *
 * <p>홈페이지 칸은 <b>자리만 잡아뒀다.</b> 무엇을 넣을지 아직 정하지 않았는데,
 * 임시로 바깥 링크를 걸어두면 그 상태로 굳어 버린다. 비워두면 다음에 반드시 눈에 띈다.
 */
function renderTaxi() {
    const org = TAXI_ORGS[taxiOrg];
    if (!org) {
        return;
    }

    document.querySelectorAll('#taxi-pick .taxi-org').forEach(b => {
        b.classList.toggle('is-active', b.dataset.org === taxiOrg);
    });

    // tel: 은 PC 에서 아무 일도 안 일어날 수 있다. 그래서 번호를 글자로도 크게 둔다.
    const telHref = `tel:${org.tel.replace(/-/g, '')}`;

    $('taxi-body').innerHTML = `
        <div class="taxi-note">${org.name}<br>${org.note}</div>

        <div class="taxi-row">
            <div class="taxi-label">${org.telLabel}</div>
            <div class="taxi-tel">
                <span class="taxi-tel-no">${org.tel}</span>
                <a class="taxi-call" href="${telHref}">전화 걸기</a>
            </div>
        </div>

        <div class="taxi-row">
            <div class="taxi-label">홈페이지</div>
            ${taxiSiteHtml(org.site)}
        </div>
    `;
}

/**
 * 홈페이지 칸. <b>맨 윗줄이 주소이고, 그 아래가 들어가서 어디를 누르는지다.</b>
 *
 * <p>주소만 주면 반은 만 것이다. 이 사이트들은 메뉴가 여러 갈래인데 정작 하려는 일은
 * 로그인 뒤에 있거나, 이름과 다른 곳에 있다(충북은 [회원가입] 을 눌러도 가입이 안 된다).
 *
 * <p>아직 안 채운 기관은 자리만 남긴다 — 임시 링크를 걸어두면 그대로 굳는다.
 */
function taxiSiteHtml(site) {
    if (!site) {
        return '<div class="taxi-slot">여기에 넣을 내용은 다음에 정합니다.</div>';
    }

    // 주소는 http(s):// 를 떼고 보여준다 — 읽는 데 방해만 되고, 링크는 어차피 버튼이다.
    const shown = site.url.replace(/^https?:\/\//, '');

    const routes = site.routes.map(r => `
        <li class="taxi-route">
            <div class="taxi-goal">${r.goal}</div>
            <ol class="taxi-steps">${r.steps.map(s => `<li>${s}</li>`).join('')}</ol>
            ${r.warn ? `<div class="taxi-warn">${r.warn}</div>` : ''}
            ${r.fields ? taxiFieldsHtml(r.fields) : ''}
            ${r.note ? `<div class="taxi-subnote">${r.note}</div>` : ''}
        </li>`).join('');

    return `
        <div class="taxi-url">
            <span class="taxi-url-text">${shown}</span>
            <a class="taxi-open" href="${site.url}" target="_blank" rel="noopener noreferrer">열기</a>
        </div>
        <ul class="taxi-routes">${routes}</ul>
    `;
}

/**
 * 챙길 것을 항목별로 가른다 — 누가 / 무엇을 / 어떻게.
 *
 * <p>값이 배열이면 줄을 나눈다. '필요 자료'처럼 성격이 다른 서류가 섞여 있을 때
 * 한 줄로 이으면 어디까지가 한 묶음인지 안 보인다.
 */
function taxiFieldsHtml(fields) {
    const rows = fields.map(f => {
        /*
          서류 목록은 두 칸(이름 | 내용) 배치에서 빠져나와 상자 하나로 선다.
          자격 버튼 + 체크 목록이 들어가야 해서 72px 옆칸에 밀어넣으면 다 접힌다.
        */
        if (f.docs) {
            return `
                <div class="taxi-docs-card">
                    <div class="taxi-docs-title">${f.k}</div>
                    ${taxiDocsHtml(f.docs)}
                </div>`;
        }

        const lines = Array.isArray(f.v) ? f.v : [f.v];
        return `
            <div class="taxi-spec-row">
                <div class="taxi-spec-k">${f.k}</div>
                <div class="taxi-spec-v">${lines.map(v => `<div>${v}</div>`).join('')}</div>
            </div>`;
    }).join('');

    return `<div class="taxi-spec">${rows}</div>`;
}

/* ── 서류 챙기기 ───────────────────────────────────────────
   자격을 고르면 그 사람이 낼 것만 남고, 하나씩 체크해 나간다.

   체크는 브라우저에만 둔다. 서버에 두면 로그인을 요구하게 되는데,
   가입도 안 한 사람이 가입 서류를 챙기는 화면에서 로그인을 물으면 앞뒤가 안 맞는다. */

const TAXI_DOCS_KEY = 'wheelway.taxiDocs';

/** 지금 고른 자격. 기관마다 따로 기억한다. */
let taxiWho = {};

const readDocChecks = () => {
    try {
        return JSON.parse(localStorage.getItem(TAXI_DOCS_KEY)) ?? {};
    } catch {
        return {};      // 손상됐으면 빈 것으로 다시 시작한다. 체크 몇 개 때문에 화면이 죽으면 안 된다
    }
};

/** 기관·자격·서류를 묶어 키로 쓴다. 같은 '심사신청서'라도 자격이 다르면 다른 칸이다. */
const docKey = (who, name) => `${taxiOrg}|${who}|${name}`;

/** 그럼 어떻게 구하나. 링크가 있는 것은 링크로, 없는 것은 구하는 법을 글로 적는다. */
function docHowHtml(it) {
    if (it.how === 'link') {
        return `<a class="taxi-doc-link" href="${it.url}" target="_blank" rel="noopener noreferrer">${it.linkText}</a>`;
    }
    const text = it.how === 'center'
        ? '센터에서 받아 작성'
        : '가진 것을 사본·사진으로';
    return `<span class="taxi-doc-how">${text}</span>`;
}

function taxiDocsHtml(docs) {
    const who = taxiWho[taxiOrg] ?? docs[0].who;
    const group = docs.find(g => g.who === who) ?? docs[0];
    const checks = readDocChecks();

    // 자격이 하나뿐이면 고르는 버튼을 안 띄운다 — 누를 것이 하나인 버튼은 고르라는 시늉만 한다.
    const picks = docs.length < 2 ? '' : `
        <div class="taxi-who-pick">${docs.map(g => `
            <button class="taxi-who${g.who === group.who ? ' is-active' : ''}"
                    type="button" data-who="${g.who}">${g.who}</button>`).join('')}</div>`;

    /*
      ★ 체크는 <b>집에서 미리 챙길 수 있는 것</b>에만 붙인다.

        link   온라인으로 발급받는다      → 집에서 된다.  체크
        own    가진 실물을 찍어 둔다       → 집에서 된다.  체크
        center 센터에 가서 받아 작성한다   → 집에서 안 된다. 체크 없음

      가서 작성하는 것에까지 체크칸을 주면 영영 안 채워지는 줄이 남아,
      다 챙기고도 준비가 덜 된 것처럼 보인다. 그건 목록의 쓸모를 깎는다.

      체크가 없는 줄도 앞에 같은 폭을 비워 이름을 나란히 맞춘다. 들쭉날쭉하면 목록으로 안 읽힌다.
    */
    const items = group.items.map(it => {
        const key = docKey(group.who, it.name);
        const box = it.how === 'center'
            ? ''
            : `<input type="checkbox" data-doc="${key}" ${checks[key] ? 'checked' : ''}>`;
        const tag = box ? 'label' : 'div';

        return `
            <li class="taxi-doc">
                <${tag} class="taxi-doc-main${box ? '' : ' is-plain'}">
                    <span class="taxi-doc-box">${box}</span>
                    <span class="taxi-doc-name">${it.name}</span>
                </${tag}>
                ${docHowHtml(it)}
            </li>`;
    }).join('');

    return `
        ${picks}
        <ul class="taxi-doc-list">${items}</ul>
    `;
}

/* ── 출발시간 추천 ─────────────────────────────────────────
   '거리 ÷ 속도'는 신호 대기·엘리베이터를 담지 못해 항상 낙관적이다.
   그래서 실제로 걸린 시간을 재서 그 사람의 속도로 갈아탄다.

   역산은 서버를 부르지 않는다 — 거리와 속도만 있으면 나오는 계산이고,
   사용자가 목표 시각을 만질 때마다 서버에 묻는 것은 낭비다. */

let lastMeters = 0;   // 방금 찾은 경로의 거리. 역산과 기록 저장에 쓴다

/**
 * 정류장까지 몇 분 걸리나. <b>세 단계로 답한다.</b>
 *
 * <pre>
 *   ① measured   그 정류장을 재본 적 있다 → 그 실측 중앙값
 *   ② estimated  안 재봤다               → 거리 × 내 배수
 *   ③ null       배수도 아직 없다        → 숫자를 만들지 않는다
 * </pre>
 *
 * <p>①이 ②를 이긴다. 그 구간을 실제로 잰 값에는 그 길의 신호와 턱까지 들어 있어서,
 * 일반 배수로 어림한 것보다 정확하다.
 *
 * <p>②는 <b>한 번도 안 가본 정류장</b>을 메우는 자리다. 시간은 구간의 성질이지만
 * 배수는 그 사람의 성질이라 다른 구간에서 얻은 것을 그대로 옮겨 쓸 수 있다.
 * 다만 그 정류장까지의 <b>거리를 알아야</b> 하므로 정류장을 고르고 출발지도 정한 경우에만 나온다.
 *
 * <p><b>★ lastMeters 가 아니라 busStopMeters 를 본다.</b> lastMeters 는 도착지까지의
 * 거리다. 여기서 답하려는 것은 '정류장까지 몇 분'이라 구간이 다르다 — 섞으면
 * 목적지가 먼 날에는 '정류장까지 40분'이라는 엉뚱한 안내가 나간다.
 */
function minutesToStop() {
    /*
      ① 거리를 알면 언제나 이쪽이다. 경로가 바뀌어도, 처음 가는 곳이어도 계산된다.
         WALK_M_PER_MIN 은 그 사람의 실측 중앙값이라 도보 기준에 배수가 이미 반영돼 있다.
    */
    if (busStopMeters > 0) {
        return {
            minutes: Math.max(1, Math.ceil(busStopMeters / WALK_M_PER_MIN)),
            source: speedInfo.walkRatio != null ? 'personal' : 'default'
        };
    }

    /*
      ② 거리를 모를 때만 그 정류장 실측으로 물러선다.
         출발지를 안 정해 경로를 못 찾은 경우다 — 그때도 늘 가던 정류장이면 답할 수 있다.
    */
    if (speedInfo.typicalMinutes != null) {
        return { minutes: speedInfo.typicalMinutes, source: 'measured' };
    }
    return { minutes: null, source: null };
}

function renderSpeedNote() {
    const el = $('r-speed');
    const n = speedInfo.recordCount ?? 0;
    const where = stopName();

    // 로그인이 먼저다. '기록이 없다'고 하면 재보라는 뜻이 되는데, 재도 저장되지 않는다.
    if (!loginId) {
        el.textContent = '로그인하면 잰 시간을 기억해 두었다가 나갈 시각을 알려드립니다.';
        return;
    }

    const est = minutesToStop();
    const c = speedInfo.ratioCount ?? 0;

    /*
      ① 그 사람 속도로 계산했다. <b>경로가 어디든 이 길로 온다.</b>
      배수는 잰 구간이 아니라 그 사람의 성질이라, 처음 가는 정류장에도 그대로 적용된다.
      기록이 늘수록 중앙값이 안정돼 저절로 정확해진다 — 그래서 몇 건짜리인지를 밝힌다.
    */
    if (est.source === 'personal') {
        const base = baseMinutes(busStopMeters);
        const diff = est.minutes - base;

        /*
          ★ '몇 분 더'를 맨 앞에 둔다. 사용자가 실제로 쓰는 값이 그것이다 —
          '1.45배'는 근거지 행동이 아니고, 배수를 보고 분을 암산하게 만들면 안 된다.

          더 빠른 경우도 있다(전동휠체어는 4km/h 를 넘는다). '항상 느리다'고 쓰면
          그 사용자에게는 매번 틀린 말이 된다.
        */
        const head = diff === 0
            ? `도보 ${base}분 → ${est.minutes}분 · 도보 안내와 같습니다`
            : `도보 ${base}분 → ${est.minutes}분 · ${diff > 0 ? `${diff}분 더` : `${-diff}분 덜`} 걸립니다`;

        // 근거는 아래 줄로 내린다. 폭이 넓으면 아직 못 믿을 값이라는 뜻이라 같이 보여준다.
        const spread = (speedInfo.ratioLow != null && speedInfo.ratioHigh != null
                        && speedInfo.ratioLow !== speedInfo.ratioHigh)
            ? ` (${speedInfo.ratioLow}~${speedInfo.ratioHigh})` : '';

        el.textContent = `${head}\n잰 기록 ${c}건 · 도보의 ${speedInfo.walkRatio}배${spread}`
            + (c < (speedInfo.minRecords ?? 3) ? ' · 더 재면 정확해집니다' : '');
        return;
    }

    // ② 아직 한 번도 안 쟀다. 지금 숫자는 비장애인 보행 속도(4km/h)라는 것을 밝힌다.
    if (est.source === 'default') {
        el.textContent = '아직 잰 기록이 없어 도보 기준(4km/h)으로 안내합니다.'
            + ' 한 번만 재두면 그 뒤로는 모든 경로가 회원님 속도로 바뀝니다.';
        return;
    }

    // ③ 거리를 몰라 그 정류장 실측으로 답했다.
    if (est.source === 'measured') {
        el.textContent = `${where ? where + ' 까지 ' : ''}잰 기록 ${n}건 기준으로`
            + ` 평소 ${est.minutes}분 걸립니다. 출발지를 정하면 경로 거리로 더 정확해집니다.`;
        return;
    }

    // ④ 숫자를 지어내지 않는다.
    el.textContent = '출발지와 타려는 정류장을 정하면 나갈 시각을 알려드립니다.';
}

/**
 * 버스 시각에서 나갈 시각을 역산한다.
 *
 * <p><b>잰 기록만 쓴다.</b> 거리를 속도로 나누는 방식은 재보지 않은 구간에나 쓸 것이고,
 * 여기서 묻는 것은 '늘 가는 정류장까지'라 실측이 훨씬 정확하다.
 * 재본 적이 없으면 숫자를 지어내지 않고 재보라고만 한다.
 */
/**
 * 소요 시간을 <b>구간별로</b> 적는다 — 도보 따로, 버스 따로.
 *
 * <p><b>왜 나누나</b>: 한 숫자로 합치면 어디서 시간이 드는지 알 수 없다.
 * 휠체어 사용자에게 이 둘은 성격이 아주 다르다 — 도보 구간은 그 사람 속도로
 * 늘었다 줄었다 하지만(도보 12분이 누구에겐 18분이다) <b>버스에 탄 시간은 누가 타도 같다.</b>
 * 나눠야 '내가 더 걸리는 만큼'이 어디인지 보이고, 그게 이 서비스가 답해야 할 것이다.
 *
 * <p><b>★ 버스 시간은 노선 <u>편도 전체</u>다.</b> 몇 정거장을 타는지는 아직 모른다 —
 * 내릴 정류장이 정해져야 알 수 있고 그건 복합 경로가 할 일이다.
 * 그래서 '340번 전 구간' 이라고 밝혀 적는다. 밝히지 않으면 사용자가 그 시간을
 * 자기 구간으로 읽고 훨씬 이르게 계산한다.
 */
/**
 * 재는 구간을 적고, 그 자리에서 정할 수 있게 한다 — <b>출발 위치와 정류장 둘 다.</b>
 *
 * <p><b>왜 여기에도 두나</b>: 스톱워치를 누르는 사람은 이 칸을 보고 있는데,
 * 출발지를 안 정했으면 여기서 '시간만 남습니다' 라는 말을 듣는다.
 * 고칠 곳이 화면 반대편(가운데 패널)에만 있으면 그 말이 막다른 길이 된다.
 *
 * <p><b>복합 경로가 붙으면 이 줄이 그대로 첫 도보 구간이 된다.</b>
 * 길찾기가 정한 '탈 정류장'이 여기에 뜨고, 스톱워치가 재는 것도 같은 구간이라
 * 계산한 시간과 실제로 잰 시간이 같은 자리에서 만난다.
 */
/**
 * 이름 뒤에 조사를 붙인다. 받침이 있으면 앞엣것, 없으면 뒤엣것.
 *
 * <p>정류장 이름은 데이터에서 오므로 문장에 박아 쓸 수 없다 —
 * '자영고등학교 은' 처럼 틀린 조사가 그대로 화면에 나간다.
 *
 * @param pair {@code '은'}(→은/는) · {@code '이'}(→이/가) · {@code '을'}(→을/를)
 */
function josa(word, pair) {
    const map = { '은': '는', '이': '가', '을': '를', '과': '와' };
    if (!word) return '';

    const last = word.charCodeAt(word.length - 1);
    // 한글 음절이 아니면(숫자·영문) 판단할 수 없다. 그때는 앞엣것을 쓴다.
    const hangul = last >= 0xac00 && last <= 0xd7a3;
    const hasFinal = hangul && (last - 0xac00) % 28 !== 0;

    return word + (hasFinal ? pair : (map[pair] || pair));
}

/**
 * 걸어가서 <b>탈 수 있는</b> 정류장. 서버가 저상 노선의 경유 정류장으로 골라 준다.
 *
 * <p>출발지가 정해질 때 한 번 받아 들고 있는다. 정류장을 고를 때마다 부르지 않는 이유는
 * 답이 출발지에만 달려 있어서다.
 */
let boardingStops = [];
let boardingFor = null;      // 어느 출발지로 받은 값인가. 출발지가 바뀌면 다시 받는다

async function loadBoardingStops() {
    const start = picked.start;
    if (!start) { boardingStops = []; boardingFor = null; renderSeg(); return; }

    const key = `${start.lat},${start.lng}`;
    if (boardingFor === key) return;          // 같은 출발지면 다시 안 받는다
    boardingFor = key;

    try {
        const res = await fetch(`/api/bus/boarding?lat=${start.lat}&lng=${start.lng}&limit=3`);
        boardingStops = res.ok ? ((await res.json()).stops || []) : [];
    } catch (e) {
        boardingStops = [];
    }
    renderSeg();
}

/**
 * 제안 줄을 그린다. <b>늘 띄우지는 않는다.</b>
 *
 * <p>이미 그 정류장을 골랐거나, 고른 정류장이 제안만큼 가까우면 띄우지 않는다 —
 * 제대로 고른 상태에서 같은 말을 계속 하면 잔소리가 되고, 정작 필요할 때
 * (멀리 잡았을 때) 눈에 안 들어온다.
 */
function renderSegTip() {
    const tip = $('seg-tip');
    tip.innerHTML = '';

    const best = boardingStops[0];
    if (!picked.start || !best) { tip.hidden = true; return; }

    // 이미 그 정류장이면 할 말이 없다.
    if (busStop && busStop.stopId === best.stop.stopId) { tip.hidden = true; return; }

    /*
      고른 정류장이 제안보다 얼마나 먼가. 조금 먼 것은 사용자의 선택일 수 있으니
      (반대 방향을 일부러 고른 경우 등) 눈에 띄게 멀 때만 말한다.
    */
    if (busStop && busStopMeters > 0 && busStopMeters <= best.stop.distanceM * 1.5) {
        tip.hidden = true;
        return;
    }

    const nos = (best.routeNos || []).join('·');
    const msg = document.createElement('span');
    msg.className = 'seg-tip-msg';
    msg.textContent = busStop
        ? `${josa(best.stop.stopName, '은')} ${best.stop.distanceM.toLocaleString()}m 입니다`
          + (nos ? ` · ${nos}번 저상` : '')
        : `걸어서 탈 수 있는 가장 가까운 정류장은 ${best.stop.stopName}`
          + ` ${best.stop.distanceM.toLocaleString()}m` + (nos ? ` · ${nos}번 저상` : '');

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'seg-tip-go';
    btn.textContent = '이곳으로';
    btn.addEventListener('click', () => pickBusStop({
        stopId: best.stop.stopId, stopName: best.stop.stopName,
        latitude: best.stop.latitude, longitude: best.stop.longitude
    }));

    tip.appendChild(msg);
    tip.appendChild(btn);
    tip.hidden = false;
}

function renderSeg() {
    const start = picked.start;

    $('seg-start').textContent = start ? start.name : '출발지를 정하세요';
    $('seg-start').classList.toggle('is-empty', !start);

    $('seg-stop').textContent = busStop ? busStop.name : '정류장을 고르세요';
    $('seg-stop').classList.toggle('is-empty', !busStop);

    const info = $('seg-info');

    if (!start || !busStop) {
        // 무엇이 모자란지 딱 집어 말한다. '정할 수 없습니다' 로는 무엇을 할지 알 수 없다.
        info.textContent = !start && !busStop ? '둘 다 정하면 도보 시간이 나옵니다.'
            : !start ? '출발지를 정하면 도보 시간이 나옵니다.'
                : '정류장을 고르면 도보 시간이 나옵니다.';
        info.classList.add('is-empty');
        renderSegTip();      // 정류장을 아직 안 골랐을 때가 제안이 제일 필요한 순간이다
        return;
    }

    const est = minutesToStop();
    info.classList.remove('is-empty');

    /*
      거리는 있는데 시간이 없을 수 없다(같은 값에서 나온다). 그래도 둘을 나눠 쓰는 이유는
      거리를 아직 못 잰 순간(요청이 도는 중)이 있어서다 — 그때는 거리 없이 시간만 적는다.
    */
    info.textContent = (est.minutes != null ? `도보 ${est.minutes}분` : '재는 중…')
        + (busStopMeters > 0 ? ` · ${busStopMeters.toLocaleString()}m` : '');

    renderSegTip();
}

function renderLegs() {
    const box = $('legs');
    box.innerHTML = '';

    /*
      ★ 복합 경로가 떠 있으면 이 칸은 비킨다.

      이 칸은 내릴 곳을 모르던 시절의 자리다 — 그래서 버스 시간을 '노선 전 구간'으로
      적을 수밖에 없었다. 복합 경로는 그 물음에 답을 갖고 있어서, 둘을 같이 두면
      같은 노선의 시간이 두 자리에서 다른 숫자로 뜬다(12분 vs 45분).
    */
    if (planShown()) { box.hidden = true; return; }

    // 정류장을 안 골랐으면 나눌 것이 없다.
    if (!busStop) { box.hidden = true; return; }

    const rows = [];

    /* ── 도보 구간 ── */
    const walk = minutesToStop();
    if (walk.minutes != null) {
        const how = walk.source === 'personal' ? '내 속도'
            : walk.source === 'measured' ? '이 정류장 실측'
                : '도보 기준 4km/h';
        rows.push({
            kind: 'walk', label: '도보',
            time: `${walk.minutes}분`,
            sub: `${busStop.name} 까지`
                + (busStopMeters > 0 ? ` · ${busStopMeters.toLocaleString()}m` : '')
                + ` · ${how}`
        });
    } else {
        rows.push({
            kind: 'walk', label: '도보', time: '—',
            sub: '출발지를 정하면 계산됩니다'
        });
    }

    /* ── 버스 구간 ── */
    const bus = busLeg();
    rows.push(bus
        ? { kind: 'bus', label: '버스', time: `${bus.runMin}분`,
            sub: `${bus.routeNo}번 전 구간 · ${bus.originName}→${bus.destName}` }
        : { kind: 'bus', label: '버스', time: '—',
            sub: '이 정류장에는 시간표가 있는 노선이 없습니다' });

    rows.forEach(r => {
        const el = document.createElement('div');
        el.className = 'leg is-' + r.kind;
        el.innerHTML = `<span class="leg-label">${r.label}</span>`
            + `<span class="leg-time">${escapeHtml(r.time)}</span>`
            + `<span class="leg-sub">${escapeHtml(r.sub)}</span>`;
        box.appendChild(el);
    });

    /*
      합계를 내지 않는다. 지금 더할 수 있는 것은 '도보 + 노선 편도 전체' 인데
      그건 아무도 실제로 겪지 않는 시간이다. 게다가 <b>기다리는 시간</b>이 빠져 있다 —
      보은은 하루 두세 편이라 그게 제일 큰 몫일 때가 많다.
      그럴듯한 합계 하나가 나눠 적은 뜻을 통째로 지운다.
    */
    const note = document.createElement('div');
    note.className = 'legs-note';
    note.textContent = bus
        ? '버스 시간은 노선 전체를 도는 시간입니다. 몇 정거장 타는지는 내릴 곳을 정해야 알 수 있습니다.'
        : '';
    if (note.textContent) box.appendChild(note);

    box.hidden = false;
}

/**
 * 이 정류장에서 시간표를 아는 노선 하나를 고른다.
 *
 * <p>저상 노선을 먼저 본다 — 휠체어로 탈 수 있는 것이 그것뿐이라, 일반 노선의
 * 소요시간을 보여주면 탈 수도 없는 버스로 계획을 세우게 된다.
 * 저상이 없으면 아무 노선이나(있는 것 중 첫 번째) 보여주되, 그것도 없으면 null 이다.
 */
function busLeg() {
    const all = (busBoard && busBoard.routes || [])
        .map(r => r.timetable)
        .filter(t => t && t.runMin != null);

    return all.find(t => t.lowFloorRoute) || all[0] || null;
}

/* ── 복합 경로 ─────────────────────────────────────────────
   걸어서 → 버스 → 걸어서. 이 화면이 답해야 할 마지막 물음이다.

   ★ 도보 분은 여기서 만든다. 서버는 미터만 준다 — 그 사람의 실측 배수(WALK_M_PER_MIN)를
   들고 있는 쪽이 화면이라, 서버가 4km/h 로 계산해 보내면 같은 구간이 화면의 다른 자리
   (경로 요약·재는 구간)와 다른 분으로 떠서 어느 쪽이 맞는지 알 수 없게 된다.

   ★ 버스에 탄 시간에는 배수를 곱하지 않는다. 그건 그 사람의 속도와 아무 상관이 없다.
   도보에만 곱하는 것이 이 화면이 하는 말의 요점이다 — '내가 더 걸리는 만큼'이 어디인지. */

let plan = null;        // 마지막으로 받은 안 { walkOnly, plans, message }
let planFor = null;     // 어느 (출발,도착) 으로 받은 값인가. 바뀌면 다시 받는다
let planSeq = 0;        // 늦게 온 응답이 새 화면을 덮지 않게
let planAt = 0;         // 그 안을 언제 받았나. 도착 시각의 기준점이다 (도착판의 busBoardAt 과 같은 값)
let planDrawn = -1;     // 지금 지도에 그려져 있는 안의 자리
let planLines = [];     // 그 선들

/**
 * 사용자가 고른 안의 자리. <b>선을 치워도 이 값은 안 지운다.</b>
 *
 * <p>탭을 떠날 때 선을 치우는데, 돌아와서 다시 그리지 않으면 패널에는 안이 있고
 * 지도에는 아무것도 없는 상태가 된다 — 마커와 점선에서 똑같은 일을 이미 두 번 겪었다.
 * 그때 무엇을 다시 그릴지가 이 값이다. planDrawn 은 '지금 그려져 있는 것'이라 역할이 다르다.
 */
let planPick = 0;

/**
 * 지금 받는 중인가.
 *
 * <p><b>이게 없으면 조용히 거짓말을 한다.</b> 같은 구간으로 loadPlan 이 한 번 더 불리는 일이
 * 흔한데(도착지 지정 → 경로 탐색 완료, 둘 다 renderPanes 를 부른다) 그때 아직 응답이 안 왔으면
 * plan 이 null 이라 '계산하지 못했습니다' 가 뜬다. 실제로는 잘 도는 중이었다.
 */
let planLoading = false;

/** 지금 복합 경로를 보여줄 수 있는 상태인가. */
const planReady = () => activeTab === 'bus' && !!picked.start && !!picked.end;

/** 안이 실제로 떠 있는가. 구간별 시간(legs)이 자리를 비켜야 하는지 판단한다. */
const planShown = () => planReady() && !!plan && (plan.plans || []).length > 0;

async function loadPlan() {
    if (!planReady()) {
        plan = null;
        planFor = null;
        clearPlanLines();
        renderPlan();
        return;
    }

    const key = `${picked.start.lat},${picked.start.lng}>${picked.end.lat},${picked.end.lng}`;
    if (planFor === key) {
        // 같은 구간이면 다시 받지 않는다. 그리기만 한다 — 아직 도는 중이면 그렇다고 적는다.
        renderPlan(planLoading ? '가는 방법을 찾는 중…' : undefined);

        // ★ 지도에도 되살린다. 탭을 떠날 때 치웠으므로 여기서 안 그리면
        //   패널에는 안이 있는데 지도에는 아무것도 없는 상태가 된다.
        if (!planLines.length && plan && (plan.plans || []).length) {
            drawPlan(Math.min(planPick, plan.plans.length - 1));
        }
        return;
    }
    planFor = key;

    const seq = ++planSeq;
    plan = null;
    planLoading = true;
    clearPlanLines();
    renderPlan('가는 방법을 찾는 중…');

    const url = `/api/bus/plan?startLat=${picked.start.lat}&startLng=${picked.start.lng}`
        + `&endLat=${picked.end.lat}&endLng=${picked.end.lng}&limit=3`;

    try {
        const res = await fetch(url);
        const data = await res.json();
        if (seq !== planSeq) return;        // 그 사이 출발·도착이 바뀌었다
        plan = res.ok ? data : null;
        planAt = Date.now();
    } catch (e) {
        if (seq !== planSeq) return;
        plan = null;
    } finally {
        // 늦게 온 응답이면 planSeq 가 이미 올라가 있고, 그 새 요청이 이 값을 다시 세운다.
        if (seq === planSeq) planLoading = false;
    }

    renderPlan();

    // 제일 나은 안을 지도에 그려 둔다. 정류장은 건드리지 않는다 —
    // 스톱워치와 도착판이 사용자가 고른 정류장에서 조용히 옮겨가면 안 된다.
    if (plan && (plan.plans || []).length) {
        drawPlan(0);
    }

    // 구간별 시간이 이 안과 겹치므로 자리를 다시 잡는다.
    renderLegs();
}

function renderPlan(loading) {
    const box = $('plan');
    const list = $('plan-list');
    const note = $('plan-note');

    list.innerHTML = '';
    note.hidden = true;

    if (!planReady()) {
        box.hidden = true;
        return;
    }
    box.hidden = false;

    if (loading) {
        note.textContent = loading;
        note.hidden = false;
        return;
    }
    if (!plan) {
        note.textContent = '가는 방법을 계산하지 못했습니다. 잠시 뒤 다시 시도해 주세요.';
        note.hidden = false;
        return;
    }

    /*
      ★ 도보만이 늘 맨 위다. 총 시간으로 줄을 세우면 버스가 언제나 이기는 것처럼 보인다 —
      버스 쪽 숫자에는 기다리는 시간이 빠져 있기 때문이다.
    */
    /*
      ★ 화면에 적힌 숫자 순서대로 세운다.

      서버가 준 순서는 '타고 가는 시간' 기준인데, 화면에 적히는 숫자에는 기다리는 시간이
      들어갈 수도 있다(지금 오는 차가 있을 때). 그러면 35분짜리가 30분짜리 위에 서서,
      사용자가 목록의 순서를 믿을 수 없게 된다.
    */
    const rows = (plan.plans || []).map((p, i) => ({ p, i, t: planTotal(p) }));
    rows.sort((a, b) => a.t.total - b.t.total);

    if (plan.walkOnly) {
        list.appendChild(walkOnlyRow(plan.walkOnly, rows[0] && rows[0].t));
    }
    rows.forEach(r => list.appendChild(busPlanRow(r.p, r.i, r.t)));

    if (plan.message) {
        note.textContent = plan.message;
        note.hidden = false;
    } else if (!plan.walkOnly && !(plan.plans || []).length) {
        note.textContent = '갈 수 있는 길을 찾지 못했습니다.';
        note.hidden = false;
    }
}

/**
 * 처음부터 끝까지 걷는 안. <b>비교의 기준선</b>이라 버스 안이 없어도 뜬다.
 *
 * @param best 가장 나은 버스 안의 계산값. 없으면 {@code null}
 */
function walkOnlyRow(leg, best) {
    const row = document.createElement('div');
    row.className = 'plan-row is-walk';

    const min = walkMinutes(leg.meters);

    row.innerHTML = `<div class="plan-row-head">`
        + `<span class="plan-kind">도보만</span>`
        + `<span class="plan-time">${min}분</span>`
        + `<span class="plan-tail">지금 출발</span></div>`
        + `<div class="plan-sum">${leg.meters.toLocaleString()}m · 계단과 공사 구간을 피한 길`
        + `${speedInfo.walkRatio != null ? ' · 내 속도 기준' : ' · 도보 기준 4km/h'}</div>`;

    /*
      ★ 버스가 근소하게 빠를 때는 그 숫자를 믿지 말라고 적는다.

      기다리는 시간이 안 들어간 값이라, 몇 분 차이는 정류장에서 서 있는 동안 그대로
      뒤집힌다. 청주는 배차가 촘촘해 덜 극단적이지만 짧은 거리에서는 거의 늘 걷는 쪽이 이기고,
      배차가 드문 지역(보은은 하루 두세 편)에서는 두 시간을 기다리게 된다.

      기다림이 이미 값에 들어간 안(catchable)에는 붙이지 않는다 — 그건 진짜 걸리는 시간이라
      더 뺄 것이 없다.
    */
    if (best && !best.catchable && best.total > min - 5) {
        const hint = document.createElement('div');
        hint.className = 'plan-hint';
        hint.textContent = '아래 버스 시간에는 기다리는 시간이 빠져 있습니다.'
            + ' 이 정도 차이면 걸어가는 편이 빠를 수 있습니다.';
        row.appendChild(hint);
    }
    return row;
}

/**
 * 이 안이 <b>지금 나가면 몇 분</b>인가. 기다리는 시간을 아는 만큼 넣는다.
 *
 * <p><b>왜 이걸 갈라야 하나</b>: 기다리는 시간을 빼고 계산하면 버스가 늘 이기는 것처럼
 * 보이는데 실제로는 진다. 그렇다고 모를 때 아무 값이나 넣으면 그건 지어낸 숫자다.
 *
 * <pre>
 *   지금 오는 저상차가 있고 탈 수 있다   도착까지 + 버스 + 도보 = <b>진짜 걸리는 시간</b>
 *   그 밖                                도보 + 버스 + 도보    · '기다림 별도'라고 밝힌다
 * </pre>
 *
 * <p>앞엣것에는 정류장에서 서 있는 시간이 들어 있다. 그게 사용자가 실제로 겪는 시간이고,
 * '도보만' 안과 같은 자로 잰 값이라 비로소 나란히 놓고 고를 수 있다.
 */
function planTotal(p) {
    const w1 = walkMinutes(p.walk1.meters);
    const w2 = walkMinutes(p.walk2.meters);

    // 받은 때를 기준으로 남은 시간. '지금'으로 잡으면 다시 그릴 때마다 뒤로 밀린다.
    const leftMin = p.arriveSec != null
        ? ((planAt || Date.now()) + p.arriveSec * 1000 - Date.now()) / 60000
        : null;

    // 딱 맞는 경우를 '탄다'고 하면 뛰게 만든다. 1분을 남겨 둔다.
    const catchable = leftMin != null && leftMin >= w1 + 1;

    return {
        w1, w2, leftMin, catchable,
        total: catchable
            ? Math.ceil(leftMin) + p.ride.minutes + w2
            : w1 + p.ride.minutes + w2
    };
}

/** 버스를 타는 안 한 줄. */
function busPlanRow(p, i, t) {
    const row = document.createElement('div');
    row.className = 'plan-row is-bus' + (planDrawn === i ? ' is-drawn' : '');

    const w1 = t.w1;
    const w2 = t.w2;

    // 기다리는 시간이 값에 들어갔는지를 숫자 옆에 밝힌다. 같은 '35분'이라도 뜻이 다르다.
    const tail = t.catchable
        ? `기다림 ${Math.max(0, Math.round(t.leftMin - w1))}분 포함`
        : '기다리는 시간 별도';

    row.innerHTML = `<div class="plan-row-head">`
        + `<span class="plan-kind">버스</span>`
        + `<span class="plan-time">${t.total}분</span>`
        + `<span class="plan-tail">${tail}</span></div>`
        + `<div class="plan-sum">${escapeHtml(p.routeNo)}번 저상 · `
        + `${escapeHtml(p.board.stopName)} 에서 타서 ${escapeHtml(p.alight.stopName)} 에서 내림</div>`;

    const legs = document.createElement('div');
    legs.className = 'plan-legs';
    legs.appendChild(planLeg('walk', `도보 ${w1}분`,
        `${escapeHtml(p.board.stopName)} 까지 · ${p.walk1.meters.toLocaleString()}m`));
    legs.appendChild(planLeg('bus', `버스 ${p.ride.minutes}분`,
        `${escapeHtml(p.routeNo)}번 · ${p.ride.stopCount} 정거장 · ${p.ride.meters.toLocaleString()}m`));
    legs.appendChild(planLeg('walk', `도보 ${w2}분`,
        `목적지까지 · ${p.walk2.meters.toLocaleString()}m`));
    row.appendChild(legs);

    /*
      ★ '탈 수 있는가' 는 걸어가는 시간까지 봐야 답이 나온다.
      3분 뒤 오는 차는 정류장까지 6분 걸리는 사람에게는 없는 차다.
      그 판단을 서버에 맡기지 않는 이유가 이것이다 — 개인 속도는 여기에만 있다.
    */
    const wait = planWait(p, w1, t);
    if (wait) {
        const el = document.createElement('div');
        el.className = 'plan-wait' + (wait.miss ? ' is-miss' : '');
        el.textContent = wait.text;
        row.appendChild(el);
    }

    const src = rideSourceNote(p.ride.source);
    if (src) {
        const el = document.createElement('div');
        el.className = 'plan-src';
        el.textContent = src;
        row.appendChild(el);
    }

    const go = document.createElement('button');
    go.type = 'button';
    go.className = 'plan-go';
    go.textContent = '이 경로로';
    go.addEventListener('click', () => usePlan(i));
    row.appendChild(go);

    // 줄 아무 데나 눌러도 지도에 그려진다. 고르는 것과 정하는 것은 다른 동작이다.
    row.addEventListener('click', (e) => {
        if (e.target !== go) drawPlan(i);
    });

    return row;
}

function planLeg(kind, time, what) {
    const el = document.createElement('div');
    el.className = 'plan-leg is-' + kind;
    el.innerHTML = `<span class="plan-leg-time">${escapeHtml(time)}</span>`
        + `<span class="plan-leg-what">${what}</span>`;
    return el;
}

/**
 * 기다리는 시간에 대해 <b>아는 만큼만</b> 말한다.
 *
 * <pre>
 *   ① 지금 저상차가 오고 있다   몇 분 뒤 · 걸어가는 시간과 견줘 탈 수 있는지까지
 *   ② 시간표가 있다             다음 편이 이 정류장을 지나는 시각(추정)
 *   ③ 둘 다 없다                모른다고 말한다. 배차 간격을 지어내지 않는다
 * </pre>
 */
function planWait(p, walkMin, t) {
    if (p.arriveSec != null) {
        /*
          ★ '몇 분 뒤' 가 아니라 <b>시각</b>으로 적는다.

          '18분 뒤 도착' 은 받은 순간에만 맞는 말이다. 사용자가 화면을 5분 두고 보면
          그 문장은 조용히 틀린 값이 되는데, 어디에도 틀렸다는 표시가 없다.
          시각으로 적으면 언제 읽어도 뜻이 안 변한다 — 이 화면이 하려는 말도 결국
          '몇 시에 나가나' 라서 형태가 맞다.
        */
        /*
          ★ 기준점은 '지금'이 아니라 <b>받은 때</b>다. 지금으로 잡으면 다시 그릴 때마다
          도착 시각이 뒤로 밀려서, 5분 전에 받은 값이 5분 뒤 도착으로 둔갑한다.
          도착판이 busBoardAt 을 두는 것과 같은 이유다.
        */
        const at = new Date((planAt || Date.now()) + p.arriveSec * 1000);
        const leave = new Date(at.getTime() - walkMin * 60000);

        // 탈 수 있는지는 planTotal 이 '지금' 기준으로 이미 가렸다. 두 자리에서 따로 재면
        // 줄에 적힌 시간과 이 문장이 어긋난다.
        const miss = !t.catchable;

        return {
            miss,
            text: miss
                ? `지금 오는 ${p.routeNo}번 저상차는 약 ${hhmm(at)} 도착이라`
                  + ` 정류장까지 ${walkMin}분이면 놓칩니다. 그다음 차 시각은 알 수 없습니다.`
                : `${p.routeNo}번 저상차가 약 ${hhmm(at)} 도착합니다.`
                  + ` 정류장까지 ${walkMin}분이니 ${hhmm(leave)} 까지 나가세요.`
        };
    }

    const tt = planTimetableWait(p.timetable);
    if (tt) {
        return { miss: false, text: `시간표 기준 다음 차 · ${tt}` };
    }
    return {
        miss: false,
        text: '지금 오고 있는 저상차가 없습니다. 다음 차 시각은 알 수 없습니다.'
    };
}

/** 시간표에서 다음 편이 <b>이 정류장을 지나는</b> 시각. 출발 시각을 그대로 쓰면 지나간 차를 기다린다. */
function planTimetableWait(t) {
    if (!t) return null;

    const out = [];
    if ((t.nextFromOrigin || [])[0]) {
        out.push(`${t.originName || ''}→${t.destName || ''} ${passAt(t.nextFromOrigin[0], t.toStopMin, t.runMin)}`);
    }
    if ((t.nextFromDest || [])[0]) {
        out.push(`${t.destName || ''}→${t.originName || ''} ${passAt(t.nextFromDest[0], t.fromDestMin, t.runMin)}`);
    }
    return out.length ? out.join(' · ') : null;
}

/**
 * 버스 시간이 어디서 온 값인지 밝힌다.
 *
 * <p>어림한 값과 잰 값을 같은 얼굴로 내보내면 안 된다. 특히 {@code assumed} 는
 * 그 노선을 한 번도 못 잰 상태라, 실제와 몇 분씩 어긋날 수 있다.
 */
function rideSourceNote(source) {
    if (source === 'timetable') return '버스 시간은 시간표의 편도 소요시간을 구간 길이로 나눈 추정입니다.';
    if (source === 'observed') return '버스 시간은 이 노선의 실제 주행 속도를 잰 값으로 계산했습니다.';
    return '버스 시간은 평균 속도로 어림한 값입니다. 이 노선을 잰 기록이 쌓이면 정확해집니다.';
}

/** 도보 거리를 그 사람의 분으로. 화면 어디서나 같은 자를 쓴다. */
const walkMinutes = (meters) => Math.max(1, Math.ceil(meters / WALK_M_PER_MIN));

/**
 * 안 하나를 지도에 그린다. <b>정류장은 바꾸지 않는다</b> — 그건 [이 경로로] 가 하는 일이다.
 *
 * <p>도보 두 구간은 점선, 버스 구간은 실선이다. 버스 선을 다른 색으로 두는 이유는
 * 그 구간만 성격이 다르기 때문이다 — 우리가 계산한 길이 아니라 정류장을 이은 선이라
 * 실제 노선 모양이 아니다(TAGO 가 노선 선형을 주지 않는다).
 */
function drawPlan(i) {
    clearPlanLines();

    const p = ((plan && plan.plans) || [])[i];
    if (!p) return;

    planDrawn = i;
    planPick = i;

    const line = (path, color, style, weight, z) => {
        const pts = path.map(q => new kakao.maps.LatLng(q[0], q[1]));
        planLines.push(new kakao.maps.Polyline({
            path: pts, strokeWeight: weight, strokeColor: color,
            strokeOpacity: .9, strokeStyle: style, zIndex: z, map: map
        }));
        return pts;
    };

    line(p.walk1.path, '#1565c0', 'shortdash', 6, 3);
    line(p.ride.path, '#00897b', 'solid', 8, 2);
    line(p.walk2.path, '#1565c0', 'shortdash', 6, 3);

    // 어느 줄이 그려졌는지 표시만 다시 한다(다시 받지 않는다).
    document.querySelectorAll('#plan-list .plan-row.is-bus').forEach((el, idx) => {
        el.classList.toggle('is-drawn', idx === i);
    });
}

function clearPlanLines() {
    planLines.forEach(l => l.setMap(null));
    planLines = [];
    planDrawn = -1;
}

/**
 * 이 안으로 정한다 — <b>탈 정류장을 그 안의 것으로 바꾼다.</b>
 *
 * <p>그러면 도착판·스톱워치·나갈 시각 역산이 모두 같은 정류장을 보게 된다.
 * 계산한 시간과 실제로 재는 시간이 같은 구간에서 만나는 자리가 여기다.
 */
function usePlan(i) {
    const p = ((plan && plan.plans) || [])[i];
    if (!p) return;

    drawPlan(i);
    pickBusStop({
        stopId: p.board.stopId, stopName: p.board.stopName,
        latitude: p.board.latitude, longitude: p.board.longitude
    });

    // 안 전체가 화면에 들어오게 맞춘다. 누른 사람의 뜻이 '이걸 보겠다' 이므로 여기서는 옮긴다.
    const b = new kakao.maps.LatLngBounds();
    [p.walk1.path, p.ride.path, p.walk2.path].forEach(path =>
        path.forEach(q => b.extend(new kakao.maps.LatLng(q[0], q[1]))));
    map.setBounds(b);

    setStatus(`${p.routeNo}번 저상버스 · ${p.board.stopName} 에서 탑니다.`, 'ok');
}

function renderLeave() {
    const out = $('r-leave');
    const v = $('r-arrive').value;
    const est = minutesToStop();
    const minutes = est.minutes;

    if (!v || minutes == null) {
        out.hidden = true;
        out.textContent = '';   // 숨기기만 하면 옛 안내가 남아 있다가 다시 켤 때 스쳐 보인다
        return;
    }

    // 버스 시각을 오늘 날짜에 얹는다. 이미 지난 시각이면 내일로 본다 —
    // 밤에 '내일 아침 첫차'를 넣는 것이 자연스러운 사용이다.
    const [hh, mm] = v.split(':').map(Number);
    const target = new Date();
    target.setHours(hh, mm, 0, 0);
    if (target.getTime() <= Date.now()) {
        target.setDate(target.getDate() + 1);
    }

    const leave = new Date(target.getTime() - minutes * 60000);
    const late = leave.getTime() <= Date.now();

    // 아직 한 번도 안 잰 상태면 그 숫자가 4km/h 기본값이라는 것을 밝힌다.
    const how = est.source === 'default' ? ' (도보 기준)' : '';

    out.hidden = false;
    out.innerHTML = late
        ? `지금 나가도 <b>${minutes}분</b>${how} 걸려 이 버스는 놓칩니다.`
        : `<b>${hhmm(leave)}</b> 에 나가세요 · 정류장까지 ${minutes}분${how}`;
}

const hhmm = (d) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;

/* ── 스톱워치 ─────────────────────────────────────────────
   재는 것은 '경로 전체'가 아니라 <b>집에서 타려는 버스가 서는 정류장까지</b> 한 구간이다.
   같은 정류장을 반복해서 가기 때문에, 거리를 속도로 나누는 것보다
   '지난번에 몇 분 걸렸나'가 훨씬 정확하다.

   진행 상태를 서버에 두지 않는 이유: [시작]만 누르고 안 돌아오는 일이 반드시 생기는데,
   그러면 '도착 안 한 행'이 영영 남아 따로 치워야 한다. */

let swTimer = null;   // 화면의 숫자를 1초마다 갱신하는 타이머

function readTracking() {
    try {
        return JSON.parse(localStorage.getItem(TRACK_KEY));
    } catch (e) {
        return null;
    }
}

/** 경과 시간을 mm:ss 로. 한 시간을 넘기면 h:mm:ss 로 늘린다. */
function elapsedText(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    const p = (n) => String(n).padStart(2, '0');
    return s >= 3600
        ? `${Math.floor(s / 3600)}:${p(Math.floor(s % 3600 / 60))}:${p(s % 60)}`
        : `${p(Math.floor(s / 60))}:${p(s % 60)}`;
}

function renderTrack() {
    const t = readTracking();
    const box = document.querySelector('.sw');
    const btn = $('sw-btn');

    if (swTimer) { clearInterval(swTimer); swTimer = null; }

    if (t) {
        box.classList.add('is-running');
        btn.textContent = '정류장 도착';
        btn.classList.add('is-tracking');
        $('sw-hint').textContent = '재는 중입니다. 정류장에 닿으면 눌러 주세요.';

        const tick = () => { $('sw-time').textContent = elapsedText(Date.now() - t.startedMs); };
        tick();
        swTimer = setInterval(tick, 1000);
    } else {
        box.classList.remove('is-running');
        btn.textContent = '시작';
        btn.classList.remove('is-tracking');
        $('sw-time').textContent = '00:00';
        /*
          ★ 출발지가 없으면 미리 알려준다.

          거리를 모르는 기록은 '몇 분 걸렸나'로만 남고 <b>도보 대비 분석에는 못 들어간다</b>.
          그 분석이 처음 가는 정류장까지 계산해 주는 본체라, 이게 안 쌓이면 기능이 안 큰다.

          누른 뒤에 말하면 늦다 — 이미 나선 사람에게 되돌아가라고 할 수는 없다.
          그래서 [시작] 을 누르기 전에 이 자리에서 말한다.
        */
        $('sw-hint').textContent = !loginId
            ? '기록은 로그인해야 남길 수 있습니다.'
            : picked.start
                ? '집에서 나설 때 눌러 두고, 정류장에 도착하면 다시 누르세요.'
                : '출발지를 정하고 재면 도보 대비 분석에도 쓰입니다. 지금 재면 시간만 남습니다.';
    }

    // 눌러봐야 비로소 거절당하는 흐름을 피한다 — 누르기 전에 못 쓴다는 것이 보여야 한다.
    btn.disabled = !loginId;
}

/* ── 잰 기록 ───────────────────────────────────────────────
   ★ 2026-08-18: 브라우저(localStorage) → 서버(WALK_RECORDS) 로 옮겼다.
   좌표·거리 NOT NULL 을 풀고 STOP_NAME 을 더해서 이제 넣을 수 있다.
   서버가 원본이라 기기를 바꿔도 기록이 따라온다.

   ★ 진행 중인 측정(wheelway.tracking)만 브라우저에 남긴다 — 이건 의도된 것이다.
   [시작]만 누르고 안 돌아오는 일이 반드시 생기는데, 서버에 두면 '도착 안 한 행'이
   영영 남아 따로 치워야 한다. 완결된 것만 서버로 보낸다.

   기록은 <b>정류장별로</b> 묶는다. 집→정류장A 는 8분, 집→정류장B 는 12분인데
   한 통에 섞으면 중앙값이 둘 다 틀린 값이 된다. 묶는 일은 이제 서버가 한다.

   ★ 정류장은 오른쪽 정류장 칸에서 고른 곳(busStop)이다. 도착지 칸이 아니다.
   전에는 도착지 칸을 그대로 썼는데, 복합 경로가 들어오면 도착지는 진짜 목적지가 되므로
   그대로 두면 집→정류장 기록이 목적지 이름으로 묶인다 — 위에서 말한 그 문제로 되돌아간다. */

/** 지금 기준이 되는 정류장 이름. 안 고른 상태도 하나의 묶음으로 본다. */
const stopName = () => (busStop ? busStop.name : null);

/** 서버에 보낼 정류장 값. 빈 문자열이면 서버가 '정류장 미지정' 묶음으로 읽는다. */
const stopParam = () => encodeURIComponent(stopName() ?? '');

/** 잘못 눌렀을 때 걸러낼 하한. 서버의 walk-min-sec 와 같은 값이다(서버도 다시 검사한다). */
const REC_MIN_SEC = 30;

/**
 * 이보다 오래 걸린 기록은 목록에서 눈에 띄게 한다.
 *
 * <p><b>기계로 지우지는 않는다.</b> 서버가 거르는 것은 4시간이 넘는 것뿐인데,
 * [도착] 누르는 걸 잊어 1시간이 찍힌 기록은 그 사이를 그냥 통과해 중앙값을 끌어당긴다.
 * 그렇다고 30분을 넘으면 자동으로 버리게 하면, 정말 30분 걸리는 사람의 기록이 사라진다.
 * 집에서 정류장까지 30분은 드무니 <b>표시만 하고 지울지는 사람이 정한다.</b>
 */
const REC_LONG_SEC = 1800;   // 30분

/** 지금 정류장까지 잰 기록. 서버가 원본이고 이건 그린 것을 들고 있는 사본이다. */
let myRecords = [];

/** 늦게 온 응답이 새 정류장 화면을 덮지 않게. 정류장을 빠르게 바꾸면 실제로 엇갈린다. */
let walkSeq = 0;

/**
 * 속도와 기록을 서버에서 다시 읽고 화면을 그린다.
 *
 * <p>정류장이 바뀔 때마다 부른다 — 정류장이 바뀌면 '평소 몇 분'도 그 묶음의 값으로 갈아타야 한다.
 */
async function reloadWalk() {
    if (!loginId) {
        // 비로그인은 부르지 않는다. 401 이 뻔한데 부르면 콘솔만 빨개진다.
        speedInfo = { typicalMinutes: null, recordCount: 0 };
        myRecords = [];
        renderSpeedNote();
        renderRecords();
        renderLeave();
        return;
    }

    const seq = ++walkSeq;
    try {
        const [speed, records] = await Promise.all([
            fetch(`/api/walk/speed?stop=${stopParam()}`).then(r => r.json()),
            fetch(`/api/walk?stop=${stopParam()}`).then(r => r.json())
        ]);
        if (seq !== walkSeq) return;      // 그 사이 정류장을 바꿨다. 옛 응답은 버린다

        /*
          세션이 끊긴 경우(401). 서버를 다시 띄우면 실제로 이렇게 된다.
          이걸 안 잡으면 '아직 잰 기록이 없습니다' 가 떠서, 로그인이 풀린 줄 모르고
          재보다가 저장에 실패한다. 화면 상태를 비로그인으로 되돌려 먼저 알린다.
        */
        if (speed && speed.ok === false) {
            loginId = '';
            speedInfo = { typicalMinutes: null, recordCount: 0 };
            myRecords = [];
            renderTrack();
            renderSpeedNote();
            renderRecords();
            renderLeave();
            return;
        }

        speedInfo = speed;
        myRecords = Array.isArray(records) ? records : [];

        /*
          ★ 여기서 도보 기준값을 그 사람 값으로 갈아탄다.
          이 대입이 없어서 지금까지 66.7(비장애인 4km/h)이 끝까지 유지됐다 —
          서버는 계속 개인 속도를 내려주고 있었는데 화면이 안 받았다.

          기록이 모자라면 서버가 66.7 을 그대로 준다. 즉 조건을 여기서 또 볼 필요가 없다.
        */
        if (speedInfo.mPerMin > 0) {
            WALK_M_PER_MIN = speedInfo.mPerMin;
        }

        // 경로를 보고 있으면 시간 표시도 새 속도로 다시 그린다.
        if (lastMeters > 0) {
            renderRouteTime();
        }
    } catch (e) {
        if (seq !== walkSeq) return;
        speedInfo = { typicalMinutes: null, recordCount: 0 };
        myRecords = [];
        setStatus('잰 기록을 불러오지 못했습니다.', 'fail');
    }

    renderSpeedNote();
    renderRecords();
    renderLeave();
    renderLegs();
    renderSeg();

    // 복합 경로의 도보 분도 이 속도로 계산한 값이라 같이 다시 그린다. 다시 받지는 않는다.
    renderPlan();
}

async function toggleTrack() {
    if (!loginId) {
        setStatus('기록을 남기려면 로그인이 필요합니다.', 'fail');
        return;
    }

    const t = readTracking();

    if (!t) {
        // 위치를 묻지 않는다. 출발할 때 누르는 버튼이 권한 창에 막히면 안 된다.
        localStorage.setItem(TRACK_KEY, JSON.stringify({ startedMs: Date.now() }));
        renderTrack();

        /*
          ★ 막지는 않는다. 이미 문을 나서는 사람을 세워 출발지부터 정하라고 하면
          스톱워치가 못 쓸 물건이 된다. 대신 이 기록이 어디까지 쓰이는지는 밝힌다.

          거리를 모르면 '몇 분 걸렸나'로만 남고 도보 대비 분석에는 못 들어간다.
          측정하는 동안 계속 보이도록 상태줄이 아니라 버튼 아래에 남긴다.
        */
        const noStart = !picked.start;
        $('sw-msg').textContent = noStart
            ? '출발지가 없어 이 기록은 시간만 남습니다. 돌아와서 출발지를 정해도 이번 것은 분석에 안 들어갑니다.'
            : '';

        setStatus(noStart
            ? '출발했습니다. 다음부터는 출발지를 정하고 재면 분석에도 쓰입니다.'
            : '출발했습니다. 정류장에 도착하면 다시 눌러 주세요.', 'ok');
        return;
    }

    const started = new Date(t.startedMs);
    const arrived = new Date();
    const sec = Math.round((arrived.getTime() - started.getTime()) / 1000);
    const mins = Math.max(1, Math.round(sec / 60));

    localStorage.removeItem(TRACK_KEY);

    if (sec < REC_MIN_SEC) {
        // 잘못 눌렀을 때다. 남기면 '평소 1분'이 돼서 안내가 무너진다.
        renderTrack();
        $('sw-msg').textContent = `${sec}초밖에 안 지나 기록하지 않았습니다.`;
        return;
    }

    renderTrack();

    const body = new URLSearchParams({
        stop: stopName() ?? '',
        startedAt: stamp(started),
        arrivedAt: stamp(arrived)
    });

    /*
      거리는 <b>경로를 찾아둔 경우에만</b> 보낸다.
      좌표는 보내지 않는다 — 검색해서 찍은 자리지 실제로 지나온 위치가 아니라서,
      START_LAT 에 넣으면 '거기서 출발했다'는 거짓이 남는다. DISTANCE_M 은
      애초에 '경로 탐색이 낸 거리'라고 정의된 칸이라 뜻이 맞는다.
      이 값이 쌓여야 재본 적 없는 구간을 어림할 m/분 이 생긴다.
    */
    /*
      ★ lastMeters(지도에 그려진 경로의 거리)를 쓰면 안 된다. 그건 <b>도착지까지</b>의
      거리인데 스톱워치가 잰 것은 <b>정류장까지</b> 한 구간이다. 도착지 칸이
      '타려는 정류장'이던 시절에는 둘이 같았지만 이제 다르다 — 그대로 넣으면
      3배쯤 되는 거리가 저장돼 개인 배수를 오염시킨다. NULL 보다 나쁘다.
    */
    if (picked.start && busStop && busStopMeters > 0) {
        body.set('distanceM', String(busStopMeters));
    }

    try {
        const res = await fetch('/api/walk', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body
        });
        const data = await res.json();

        if (!res.ok || data.ok === false) {
            $('sw-msg').textContent = data.message || '기록을 저장하지 못했습니다.';
            setStatus(data.message || '기록을 저장하지 못했습니다.', 'fail');
            return;
        }

        // 서버가 이번 기록이 반영된 값을 같이 준다. 목록만 다시 읽으면 된다.
        speedInfo = data;
        await reloadWalk();

        /*
          거리 없이 잰 기록은 시간 중앙값에만 들어가고 '도보 대비 배수' 에는 못 들어간다.
          그 배수가 있어야 안 재본 정류장까지 어림할 수 있는데, 가만두면 3건이 안 모인다.
          그래서 한 번 알려준다 — 잔소리가 되지 않게, 아직 배수를 모를 때만.
        */
        const noDist = !body.has('distanceM');
        $('sw-msg').textContent = `${mins}분으로 기록했습니다.`
            + (noDist && speedInfo.walkRatio == null
                ? ' 출발지·도착지를 정하고 재면 다른 구간을 어림하는 데도 쓰입니다.' : '');

        setStatus(`정류장까지 ${mins}분 걸렸습니다.`, 'ok');
    } catch (e) {
        $('sw-msg').textContent = '기록을 저장하지 못했습니다. 잠시 뒤 다시 시도해 주세요.';
        setStatus('기록을 저장하지 못했습니다.', 'fail');
    }
}

/**
 * 지금 고른 정류장의 기록.
 *
 * <p><b>지우지 않고 '빼기'로 바꿨다.</b> 서버가 그렇게 만들어져 있다 —
 * 지우면 왜 뺐는지가 사라지고 되돌릴 수도 없다. 중간에 편의점을 들른 기록은
 * 빼두면 중앙값에서 빠지되 목록에는 남아 있어서, 잘못 뺐으면 되돌릴 수 있다.
 */
function renderRecords() {
    const box = $('sw-list');

    if (!loginId) {
        box.innerHTML = '<div class="sw-list-head">로그인하면 잰 기록이 여기에 쌓입니다.</div>';
        return;
    }
    if (!myRecords.length) {
        box.innerHTML = '';
        return;
    }

    box.innerHTML = `<div class="sw-list-head">${escapeHtml(stopName() ?? '정류장 미지정')}`
        + ` 까지 잰 기록</div>`;

    myRecords.slice(0, 6).forEach((r) => {
        const off = r.excludedYn === 'Y';
        const mins = Math.max(1, Math.round(r.elapsedSec / 60));

        const row = document.createElement('div');
        row.className = 'sw-row'
            + (off ? ' is-off' : '')
            + (!off && r.elapsedSec >= REC_LONG_SEC ? ' is-long' : '');

        row.innerHTML = `<span>${mins}분</span>`
            + `<span class="sw-when">${escapeHtml(r.startedAt ?? '')}`
            + `${off ? ' · 뺀 기록' : ''}</span>`;

        row.appendChild(recordAction(r, off ? '되돌리기' : '빼기', async () => {
            const res = await fetch(`/api/walk/${r.id}/excluded`
                + `?excluded=${!off}&stop=${stopParam()}`, { method: 'PUT' });
            return res;
        }));

        /*
          삭제는 빼기와 쓰임이 다르다.
            빼기  진짜 이동인데 그날만 이상했다 — 되돌릴 수 있어야 한다
            삭제  애초에 이동 기록이 아니다 — [도착] 누르는 걸 잊어 1시간이 찍혔다

          되돌릴 수 없는 일이라 한 번 묻는다. 목록이 좁아 옆 버튼과 1px 차이로
          잘못 눌리기 쉬운 자리이기도 하다.
        */
        row.appendChild(recordAction(r, '삭제', async () => {
            if (!confirm(`${mins}분 기록을 지웁니다. 되돌릴 수 없습니다.`)) {
                return null;
            }
            return fetch(`/api/walk/${r.id}?stop=${stopParam()}`, { method: 'DELETE' });
        }));

        box.appendChild(row);
    });
}

/** 기록 한 줄의 버튼. 누르는 동안 잠가서 두 번 눌리지 않게 한다. */
function recordAction(record, label, run) {
    const btn = document.createElement('button');
    btn.className = 'sw-del';
    btn.textContent = label;

    btn.addEventListener('click', async () => {
        btn.disabled = true;
        try {
            const res = await run();
            if (res === null) {       // 사용자가 확인 창에서 취소했다
                btn.disabled = false;
                return;
            }
            const data = await res.json();
            if (!res.ok || data.ok === false) {
                setStatus(data.message || '고치지 못했습니다.', 'fail');
                btn.disabled = false;
                return;
            }
            await reloadWalk();       // 목록을 다시 그리므로 이 버튼은 사라진다
        } catch (e) {
            setStatus('고치지 못했습니다.', 'fail');
            btn.disabled = false;
        }
    });
    return btn;
}

/** 서버가 받는 'yyyy-MM-dd HH:mm:ss'. toISOString 은 UTC 라 9시간이 어긋난다. */
function stamp(d) {
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
        + ` ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/* ── 최근경로 ─────────────────────────────────────────────
   서버에 사용자가 없어서 지금은 브라우저에만 남긴다.
   로그인이 붙으면 이 두 함수만 서버 저장으로 바꾸면 된다. */

function readRecent() {
    try {
        return JSON.parse(localStorage.getItem(RECENT_KEY)) || [];
    } catch (e) {
        return [];
    }
}

function pushRecent(meters, minutes) {
    const item = {
        s: { ...picked.start }, e: { ...picked.end },
        meters, minutes, at: Date.now()
    };

    const list = readRecent().filter(v =>
        !(v.s.name === item.s.name && v.e.name === item.e.name));

    list.unshift(item);
    localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, RECENT_MAX)));
    renderRecent();
}

function renderRecent() {
    const list = readRecent();
    const ul = $('recent-list');
    ul.innerHTML = '';

    if (!list.length) {
        const li = document.createElement('li');
        li.className = 'empty';
        li.textContent = '아직 없습니다.';
        ul.appendChild(li);
        return;
    }

    list.forEach(v => {
        const li = document.createElement('li');
        const b = document.createElement('button');
        b.type = 'button';
        b.innerHTML =
            `<span class="rc-line"><span class="dotm">●</span>${escapeHtml(v.s.name)}</span>`
            + `<span class="rc-line is-end"><span class="dotm">●</span>${escapeHtml(v.e.name)}</span>`
            + `<span class="rc-sub">${v.minutes}분 · ${v.meters.toLocaleString()}m</span>`;

        b.addEventListener('click', () => {
            clearAll(true);
            setPlace('start', v.s);
            setPlace('end', v.e);
            maybeRoute();
        });

        li.appendChild(b);
        ul.appendChild(li);
    });
}

/* ── 주변 정류장·저상버스 도착 ─────────────────────────────
   이 탭이 답하려는 것은 하나다 — '휠체어로 탈 수 있는 버스가 몇 분 뒤에 오나'.

   ★ 노선 단위로는 알 수 없다. 한 노선에 저상차와 일반차가 섞여 다녀서
   '저상 운행 노선' 으로 거르면 오지 않는 버스를 기다리게 된다.
   그래서 차량 단위로 알려주는 도착정보를 쓴다(서버가 vehicletp 를 읽어 lowFloor 로 정규화한다).

   제공자가 누구인지(TAGO/TOPIS) 화면은 모른다. region-id 가 서버에서 고른다. */

let busStops = [];       // 마지막으로 받은 주변 정류장
let busStopId = null;    // 고른 정류장 키. 도착정보를 부를 때 그대로 되돌려준다

/**
 * ★ 지금 고른 정류장. <b>도착지 칸(picked.end)과 별개로 들고 있다.</b>
 *
 * <p><b>왜 나눴나</b>: 전에는 정류장을 고르면 도착지 칸에 넣었다. 그때는 버스 탭이
 * 하는 일이 '집에서 정류장까지'뿐이라 '고른 정류장 = 도착지'가 성립했다.
 * 복합 경로가 들어오면 그 등식이 깨진다 — <b>도착지는 진짜 목적지</b>이고
 * 정류장은 거쳐 가는 곳이다.
 *
 * <p>안 나누면 두 가지가 조용히 틀어진다.
 * <pre>
 *   ① 기록이 목적지 이름으로 묶인다 — 집→A(8분)와 집→B(12분)가 한 통에 섞여
 *      중앙값이 둘 다 틀린 값이 된다. STOP_NAME 을 굳이 만든 이유가 이것이었다
 *   ② 거리가 전체 경로 거리로 저장된다 — 스톱워치가 재는 건 첫 도보 구간뿐인데
 *      3배쯤 되는 값이 들어간다. 이건 NULL 보다 나쁘다. NULL 은 분석에서 빠지지만
 *      틀린 값은 개인 배수를 오염시킨다
 * </pre>
 */
let busStop = null;      // { stopId, name, lat, lng }

/**
 * 출발지에서 그 정류장까지의 <b>도보 거리</b>(m). 스톱워치 기록에 같이 저장한다.
 *
 * <p>{@code lastMeters}(지도에 그려진 경로의 거리)를 쓰면 안 된다 — 그건 도착지까지의
 * 거리라 스톱워치가 잰 구간과 다르다. 그래서 정류장을 고를 때 따로 한 번 잰다.
 */
let busStopMeters = 0;

/**
 * 정류장까지의 도보 경로를 그린 선. 목적지 경로({@code routeLine})와 <b>따로 둔다.</b>
 *
 * <p>하나로 합치면 정류장을 누를 때마다 사용자가 보던 목적지 경로가 사라진다.
 * 둘은 다른 구간이고 같이 보여야 한다 — 목적지까지는 실선, 정류장까지는 점선이다.
 */
let stopLine = null;
let busBoard = null;     // { arrivals, routes }
let busBoardAt = null;   // 그 도착정보를 언제 받았나. 카운트다운의 기준점이다
let busSeq = 0;          // 늦게 온 응답이 새 화면을 덮지 않게 (순번을 안 붙였다가 실제로 겪었다)

/* ── 실시간 갱신 ───────────────────────────────────────────
   ★ 두 가지를 나눠서 돌린다. 이게 이 기능의 전부다.

     초 카운트다운  1초마다  화면만 다시 그린다. 서버를 안 부른다
     도착정보 갱신  45초마다 서버를 부른다

   1초마다 API 를 부르면 개발계정 호출량이 몇 분 만에 바닥난다. 그런데 남은 시간은
   초마다 줄어드는 게 맞다 — 받아온 arriveSec 에서 '받은 뒤 흐른 시간'을 빼면
   서버를 안 불러도 정확히 줄어든다. 중간에 버스가 막혀 늦어지는 것만 45초마다 보정한다.

   ★ 안 보일 때는 멈춘다. 탭을 떠나거나 창이 가려지면 부를 이유가 없고,
   브라우저가 백그라운드 타이머를 늘려서 어차피 제때 돌지도 않는다. */

const BUS_POLL_MS = 45000;    // 도착정보를 다시 받는 주기
const BUS_IDLE_MS = 600000;   // 10분. 켜둔 채 잊어버린 화면이 하루 종일 부르는 것을 막는다

let busTickTimer = null;   // 1초 카운트다운
let busPollTimer = null;   // 45초 재조회
let busLiveUntil = 0;      // 이 시각이 지나면 자동 갱신을 멈춘다

/**
 * '저상만' 을 <b>켜진 채로 시작한다.</b>
 *
 * <p>이 앱을 쓰는 사람에게 일반차량은 탈 수 없는 버스다. 목록에 섞여 있으면
 * 매번 눈으로 걸러야 하고, 급할 때 잘못 보고 나가게 된다.
 *
 * <p>그래도 <b>끌 수 있게 남겨둔다.</b> 동행이 있거나 상황에 따라 전체를 봐야 할 때가 있고,
 * 무엇보다 '저상이 없다'와 '버스가 없다'는 다른 말이라 사용자가 직접 확인할 길이 있어야 한다.
 * 서버가 잘라서 주지 않고 화면에서 거르는 것도 같은 이유다.
 *
 * <p>한 번 끄면 그 선택을 기억한다. 켤 때마다 다시 끄게 만들면 그것도 성가시다.
 */
const LOW_ONLY_KEY = 'wheelway.busLowOnly';

function lowOnlyPref() {
    const v = localStorage.getItem(LOW_ONLY_KEY);
    return v === null ? true : v === '1';   // 정한 적 없으면 켜짐
}

/**
 * 정류장을 찾을 기준 좌표.
 *
 * <p><b>위치 권한을 묻지 않는다.</b> 출발지를 정했으면 그곳, 아니면 지금 보고 있는
 * 지도 한가운데다. 나갈 시각을 정하려는 사람이 권한 창에 먼저 막히면 본말이 뒤집힌다.
 */
function busOrigin() {
    if (picked.start) {
        return [picked.start.lat, picked.start.lng];
    }
    const c = map.getCenter();
    return [c.getLat(), c.getLng()];
}

/**
 * 버스 API 호출.
 *
 * <p>실패를 빈 목록으로 바꾸지 않는다 — 못 부른 것과 '이 근처에 정류장이 없는 것'은
 * 사용자에게 전혀 다른 뜻이다. 서버가 503 과 함께 사람이 읽을 수 있는 이유를 주므로
 * 그것을 그대로 띄운다.
 */
async function busFetch(url) {
    const res = await fetch(url);

    let data = null;
    try {
        data = await res.json();
    } catch (e) { /* 본문이 JSON 이 아닐 수 있다. 아래에서 상태코드로 처리한다 */ }

    if (!res.ok) {
        throw new Error((data && data.message)
            || `버스 정보를 불러오지 못했습니다 (HTTP ${res.status}).`);
    }
    return data;
}

function busNote(id, text, fail) {
    const el = $(id);
    el.textContent = text || '';
    el.className = 'bus-note' + (fail ? ' is-fail' : '');
}

async function loadBusStops() {
    const [lat, lng] = busOrigin();
    const seq = ++busSeq;

    $('btn-stops').disabled = true;
    busNote('bus-stops-note', picked.start
        ? `${picked.start.name} 주변에서 찾는 중…`
        : '지도 한가운데를 기준으로 찾는 중…');

    try {
        const data = await busFetch(`/api/bus/stops?lat=${lat}&lng=${lng}`);
        if (seq !== busSeq) return;      // 그 사이 다시 눌렀다. 옛 응답은 버린다

        busStops = data.stops || [];
        busStopsOpen = true;             // 찾았으면 펼친다. 닫는 것은 사용자가 정한다
        renderBusStops();

        busNote('bus-stops-note', busStops.length
            ? (picked.start ? `${picked.start.name} 에서 가까운 순입니다.`
                            : '지도 한가운데에서 가까운 순입니다. 지도를 옮기고 다시 누르면 그 자리에서 찾습니다.')
            : '이 근처에는 정류장이 없습니다. 지도를 옮기고 다시 눌러 보세요.');
    } catch (e) {
        if (seq !== busSeq) return;
        busStops = [];
        busStopsOpen = false;
        renderBusStops();
        busNote('bus-stops-note', e.message, true);
    } finally {
        $('btn-stops').disabled = false;
    }
}

/**
 * 목록을 펼쳐 두고 있나.
 *
 * <p>닫아도 받아온 목록은 들고 있는다 — 다시 열 때 호출하지 않으려는 것이다.
 * 자리를 옮겼으면 [다시 찾기] 가 그 몫을 한다.
 */
let busStopsOpen = false;

function renderBusStops() {
    const box = $('bus-stop-list');
    box.innerHTML = '';

    // 버튼 이름이 지금 무슨 일이 일어나는지를 말해야 한다.
    $('btn-stops').textContent = busStops.length
        ? '이 근처에서 다시 찾기'
        : '이 근처 정류장 찾기';

    // 목록과 지도를 같이 맞춘다 — 닫으면 지도에서도 사라져야 한다.
    drawBusStopMarkers();

    if (!busStopsOpen || !busStops.length) {
        return;
    }

    // 목록 머리. 몇 곳인지와 닫는 길을 같이 둔다.
    const head = document.createElement('li');
    head.className = 'bus-stop-head';
    head.innerHTML = `<span>주변 정류장 ${busStops.length}곳</span>`;

    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'bus-stop-close';
    close.textContent = '닫기';
    close.addEventListener('click', () => {
        busStopsOpen = false;
        renderBusStops();
        busNote('bus-stops-note', '');
    });
    head.appendChild(close);
    box.appendChild(head);

    busStops.forEach(s => {
        const li = document.createElement('li');
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'bus-stop' + (s.stopId === busStopId ? ' is-picked' : '');
        btn.innerHTML = `<span class="bus-stop-name">${escapeHtml(s.stopName)}</span>`
            + `<span class="bus-stop-dist">${s.distanceM}m</span>`;
        btn.addEventListener('click', () => pickBusStop(s));
        li.appendChild(btn);
        box.appendChild(li);
    });
}

/* ── 지도 위 정류장 ────────────────────────────────────────
   목록만 있으면 '보은여고 206m' 와 '보은여고 217m' 중 어느 쪽이 내가 갈 곳인지 알 수 없다.
   방향이 갈리는 자리라 지도에서 위치를 봐야 정할 수 있다.

   기본 Marker 는 클릭을 자기가 먹어서 지도 클릭을 막는다. 오버레이는 통과시킨다
   (출발·도착 핀과 같은 이유). */

let busStopOverlays = [];

function clearBusStopMarkers() {
    busStopOverlays.forEach(o => o.setMap(null));
    busStopOverlays = [];
}

/**
 * 목록에 있는 정류장을 지도에 찍는다. <b>마우스를 올리면 이름이 뜬다.</b>
 *
 * <p>이름표를 늘 띄우지 않는 이유: 같은 이름이 겹쳐 있는 자리라 여덟 개가 한꺼번에 뜨면
 * 서로 가려 아무것도 못 읽는다. 필요한 하나만 짚어 보게 한다.
 */
/**
 * 정류장 표시. <b>버스 모양을 직접 그린다.</b>
 *
 * <p>카카오 지도에 그려진 아이콘은 카카오 저작물이라 가져다 쓰지 않는다.
 * 같은 뜻이 통하는 그림을 인라인 SVG 로 그리면 파일도 필요 없고 색도 상태에 따라 바꿀 수 있다.
 */
function busMarkerEl(s, picked) {
    const el = document.createElement('div');
    el.className = 'bus-mk' + (picked ? ' is-picked' : '');
    el.innerHTML =
        `<span class="bus-mk-pin">
           <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
             <path fill="currentColor" d="M6 3h12a3 3 0 0 1 3 3v8a2 2 0 0 1-1 1.7V18a1 1 0 0 1-1 1h-1a1 1
                   0 0 1-1-1v-1H7v1a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-2.3A2 2 0 0 1 3 14V6a3 3 0 0 1 3-3zm0
                   3v5h12V6H6zm1.5 7a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zm9 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z"/>
           </svg>
         </span>`
        + `<span class="bus-mk-label">${escapeHtml(s.stopName)}`
        + `${s.distanceM > 0 ? ` · ${s.distanceM}m` : ''}</span>`;

    // 지도에서 바로 고를 수 있어야 한다 — 목록으로 눈을 옮길 이유가 없다.
    el.addEventListener('click', () => pickBusStop(s));
    return el;
}

function drawBusStopMarkers() {
    clearBusStopMarkers();

    if (!map || activeTab !== 'bus') {
        return;
    }

    /*
      ★ 두 벌을 겹쳐 그린다.
        지도 범위 전체(mapStops)  회색 버스 — '여기 정류장이 있다'
        찾은 가까운 곳(busStops)  파란 버스 — 목록에 뜬 그 정류장

      같은 정류장이 두 벌에 다 있으면 목록 쪽이 이긴다. 사용자가 방금 부른 것이라서다.
    */
    const near = new Map(busStops.map(s => [s.stopId, s]));

    // 넓게 보고 있으면 회색 층은 건너뛴다. 파랑(방금 찾은 곳)은 아래에서 계속 그린다.
    const wide = map.getLevel() > BUS_MK_MAX_LEVEL;
    $('bus-zoom-hint').textContent = wide
        ? '지도를 확대하면 주변 정류장이 표시됩니다.' : '';

    (wide ? [] : mapStops).forEach(s => {
        if (near.has(s.stopId)) return;
        busStopOverlays.push(new kakao.maps.CustomOverlay({
            position: new kakao.maps.LatLng(s.latitude, s.longitude),
            content: busMarkerEl(s, s.stopId === busStopId),
            yAnchor: 1, zIndex: 4, clickable: true, map: map
        }));
    });

    if (!busStopsOpen) {
        return;
    }
    busStops.forEach(s => {
        const el = busMarkerEl(s, s.stopId === busStopId);
        el.classList.add('is-near');
        busStopOverlays.push(new kakao.maps.CustomOverlay({
            position: new kakao.maps.LatLng(s.latitude, s.longitude),
            content: el, yAnchor: 1, zIndex: 5, clickable: true, map: map
        }));
    });
}

/* ── 지도 범위 안의 정류장 ─────────────────────────────────
   근접 조회는 반경이 API 안에 박혀 있어 8곳까지만 준다. 지도를 채우려면 따로 받아야 한다.
   서버가 지역 전체(보은 838곳)를 캐시해 두므로, 지도를 옮겨도 TAGO 를 다시 부르지 않는다. */

let mapStops = [];      // 지금 화면 범위에 있는 정류장
let mapStopsSeq = 0;    // 지도를 빨리 움직이면 응답이 순서대로 오지 않는다
let mapIdleTimer = null;

/**
 * 이보다 넓게 보고 있으면 정류장을 안 그린다. <b>숫자가 작을수록 확대</b>다.
 *
 * <p>화면 크기 실측(보은읍 기준)
 * <pre>
 *   level 3    802 × 714 m     6곳    가게 이름이 보이는 수준
 *   level 4   1.6 × 1.4 km    13곳
 *   level 5   3.2 × 2.9 km    43곳    ← 여기까지 그린다
 *   level 6   6.4 × 5.7 km    80곳    상한에 걸림
 *   level 7  12.8 × 11.4 km          핀이 뭉쳐 읽히지 않는다
 * </pre>
 *
 * <p><b>5 로 끊는 근거</b>: 6.4km 는 휠체어로 정류장까지 갈 거리가 아니다.
 * 그 화면에서 정류장을 80개 찍어봐야 어느 게 어딘지 구분도 안 되면서
 * 그리기에만 100ms 를 넘게 쓴다. 3.2km 면 '걸어갈 만한 범위'가 다 들어온다.
 *
 * <p>단 <b>[이 근처 정류장 찾기] 로 받은 것은 배율과 무관하게 그린다</b> —
 * 사용자가 방금 부른 것이라 사라지면 안 된다.
 */
const BUS_MK_MAX_LEVEL = 5;

/** 화면에 그릴 상한. level 5 는 43곳이라 여유가 있고, 넘치면 화면 한가운데부터 남긴다. */
const BUS_MK_LIMIT = 60;

async function loadStopsInView() {
    if (!map || activeTab !== 'bus') {
        return;
    }
    // 안 그릴 배율이면 받아올 이유도 없다.
    if (map.getLevel() > BUS_MK_MAX_LEVEL) {
        mapStops = [];
        drawBusStopMarkers();
        return;
    }
    const b = map.getBounds();
    const sw = b.getSouthWest(), ne = b.getNorthEast();
    const seq = ++mapStopsSeq;

    try {
        const data = await busFetch('/api/bus/stops-in'
            + `?minLat=${sw.getLat()}&minLng=${sw.getLng()}`
            + `&maxLat=${ne.getLat()}&maxLng=${ne.getLng()}&limit=${BUS_MK_LIMIT}`);
        if (seq !== mapStopsSeq) return;      // 그 사이 지도를 또 옮겼다

        mapStops = data.stops || [];
        drawBusStopMarkers();
    } catch (e) {
        if (seq !== mapStopsSeq) return;
        mapStops = [];
        drawBusStopMarkers();
    }
}

/** 지도를 끄는 동안 매번 부르지 않게 잠깐 기다렸다 한 번만 부른다. */
function scheduleStopsInView() {
    if (mapIdleTimer) {
        clearTimeout(mapIdleTimer);
    }
    mapIdleTimer = setTimeout(loadStopsInView, 250);
}

/**
 * 정류장을 고른다.
 *
 * <p>고른 정류장이 <b>곧 도착지</b>다. 그래서 도착지 칸을 같이 채운다 —
 * 그래야 집→정류장 경로가 그려지고, 스톱워치 기록도 이 정류장 것으로 묶인다.
 * 칸을 따로 두면 같은 곳을 두 번 넣어야 한다.
 */
function pickBusStop(stop) {
    busStopId = stop.stopId;

    /*
      ★ 도착지 칸에 넣지 않는다. 전에는 setPlace('end', …) 했는데,
      그러면 '타려는 정류장'이 '가려는 곳'을 덮어쓴다. 복합 경로에서는 둘이 다른 것이다.
      정류장은 여기서만 들고, 스톱워치도 이 값을 본다(busStop 선언부 참고).
    */
    busStop = {
        stopId: stop.stopId,
        name: stop.stopName,
        lat: stop.latitude,
        lng: stop.longitude
    };

    renderBusStops();
    renderSeg();        // 거리를 재기 전에도 이름은 바로 바뀌어야 한다
    loadBusBoard();

    // 정류장이 바뀌면 그 정류장 묶음의 기록·개인 속도로 다시 읽는다.
    reloadWalk();

    // 집→정류장 거리도 같이 잰다. 스톱워치 기록에 붙일 값이다.
    measureToStop();
}

/**
 * 출발지 → 고른 정류장의 <b>도보 경로</b>를 찾아 지도에 그리고, 그 거리를 들고 있는다.
 *
 * <p>이 구간이 복합 경로의 <b>첫 도보 구간</b>이다. 지금은 그것만 있고 버스·나머지 도보가
 * 아직 없을 뿐이라, 여기서 그리는 선이 나중에 그대로 1구간이 된다.
 *
 * <p><b>도착지 경로와 다른 선으로 그린다.</b> 하나로 합치면 정류장을 누를 때마다
 * 사용자가 보던 목적지 경로가 까닭 없이 사라진다. 둘은 다른 구간이고 같이 보여야 한다 —
 * 목적지까지는 실선, 정류장까지는 <b>점선</b>이다.
 *
 * <p>출발지가 없으면 잴 수 없다. 그때는 0 으로 두고 기록은 시간만 남긴다 —
 * 없는 거리를 어림해 넣으면 개인 배수가 조용히 오염된다.
 */
async function measureToStop() {
    busStopMeters = 0;
    clearStopLine();
    if (!picked.start || !busStop) return;

    const seq = busSeq;
    const url = `/api/route?startLat=${picked.start.lat}&startLng=${picked.start.lng}`
        + `&endLat=${busStop.lat}&endLng=${busStop.lng}`;

    let res;
    try {
        res = await fetch(url).then(r => r.json());
    } catch (e) {
        /* 거리는 곁들이는 값이다. 못 재도 기록은 시간만으로 남는다. */
        return;
    }

    // 그 사이 다른 정류장을 골랐으면 버린다.
    if (seq !== busSeq) return;

    if (res.resultStatus !== '성공') {
        setStatus(res.resultStatus === '경로없음'
            ? '정류장까지 갈 수 있는 길을 찾지 못했습니다.'
            : '정류장까지의 경로를 계산하지 못했습니다.', 'fail');
        return;
    }

    busStopMeters = Math.round(res.distanceM);

    const points = res.path.map(p => new kakao.maps.LatLng(p[0], p[1]));
    stopLine = new kakao.maps.Polyline({
        path: points,
        strokeWeight: 6, strokeColor: '#1565c0', strokeOpacity: .9,
        // 점선. 목적지까지의 실선과 눈으로 구분돼야 '어느 선이 정류장까지인지'를 안다.
        strokeStyle: 'shortdash',
        zIndex: 3, map: map
    });

    /*
      ★ 목적지 경로를 이미 보고 있으면 화면을 옮기지 않는다.
      정류장을 하나씩 눌러 볼 때마다 지도가 튀면 비교를 할 수 없다.
      목적지가 없을 때는 이 구간이 지금 보는 전부라 맞춰 준다.
    */
    if (lastMeters <= 0) {
        const b = new kakao.maps.LatLngBounds();
        points.forEach(p => b.extend(p));
        map.setBounds(b);
    }

    renderTrack();
    renderLegs();
    renderSeg();
}

/** 정류장까지의 점선을 지운다. 정류장을 바꾸거나 버스 탭을 떠날 때. */
function clearStopLine() {
    if (stopLine) {
        stopLine.setMap(null);
        stopLine = null;
    }
}

async function loadBusBoard() {
    if (!busStopId) return;

    const seq = ++busSeq;
    $('bus-board').hidden = false;
    $('bus-board-stop').textContent = busStop ? busStop.name : '';
    $('bus-arrivals').innerHTML = '';
    busNote('bus-board-note', '도착 정보를 불러오는 중…');

    try {
        const data = await busFetch(`/api/bus/board?stopId=${encodeURIComponent(busStopId)}`);
        if (seq !== busSeq) return;

        busBoard = data;
        busBoardAt = new Date();

        // ★ 켜는 것이 그리는 것보다 먼저다. 반대로 하면 아래 문구가 아직 꺼진 상태를 보고
        //   방금 받아온 값에 '자동 갱신이 멈췄습니다' 를 붙인다.
        startBusLive();
        renderBusBoard();
    } catch (e) {
        if (seq !== busSeq) return;
        busBoard = null;
        $('bus-arrivals').innerHTML = '';
        busNote('bus-board-note', e.message, true);
        stopBusLive();          // 못 부르는 상태에서 45초마다 계속 두드릴 이유가 없다
    }
}

/* ── 실시간 갱신 켜기·끄기 ─────────────────────────────── */

/** 자동 갱신을 시작(또는 연장)한다. 사용자가 뭔가를 누르면 10분이 다시 채워진다. */
function startBusLive() {
    busLiveUntil = Date.now() + BUS_IDLE_MS;

    if (!busTickTimer) {
        busTickTimer = setInterval(tickBusBoard, 1000);
    }
    if (!busPollTimer) {
        busPollTimer = setInterval(() => {
            // 켜둔 채 자리를 뜬 화면이 하루 종일 API 를 부르는 것을 막는다.
            if (Date.now() > busLiveUntil) {
                stopBusLive();
                renderBusBoard();     // '멈췄습니다' 로 문구가 바뀐다
                return;
            }
            // 안 보이는 동안은 부르지 않는다. 어차피 볼 수 없는 값이다.
            if (document.visibilityState === 'hidden') {
                return;
            }
            loadBusBoard();
        }, BUS_POLL_MS);
    }
}

function stopBusLive() {
    if (busTickTimer) { clearInterval(busTickTimer); busTickTimer = null; }
    if (busPollTimer) { clearInterval(busPollTimer); busPollTimer = null; }
}

/** 자동 갱신이 지금 돌고 있나. */
const busLive = () => busPollTimer !== null && Date.now() <= busLiveUntil;

/**
 * 남은 시간만 1초마다 다시 그린다. <b>서버를 부르지 않는다.</b>
 *
 * <p>받아온 {@code arriveSec} 에서 '받은 뒤 흐른 시간'을 빼면 정확히 줄어든다.
 * 버스가 막혀 예정이 늦어지는 것은 45초마다 오는 갱신이 보정한다.
 */
function tickBusBoard() {
    if (!busBoard || !busBoardAt) {
        return;
    }
    document.querySelectorAll('.bus-arrival[data-sec]').forEach(el => {
        el.querySelector('.bus-eta').innerHTML =
            etaText(Number(el.dataset.sec), el.dataset.prev);
    });
}

/** 받은 뒤 흐른 시간을 뺀, 지금 기준 남은 초. */
function remainSec(arriveSec) {
    if (arriveSec == null || !busBoardAt) {
        return null;
    }
    return arriveSec - Math.floor((Date.now() - busBoardAt.getTime()) / 1000);
}

function renderBusBoard() {
    if (!busBoard) return;

    const lowOnly = $('bus-low-only').checked;
    const all = busBoard.arrivals || [];

    // lowFloor 가 null 인 것(= 저상인지 모르는 것)은 '저상만' 에서 뺀다.
    // 모르는 것을 저상으로 셈하면 탈 수 없는 버스를 기다리게 된다.
    const low = all.filter(a => a.lowFloor === true);

    /*
      ★ 걸렀더니 한 대도 안 남으면, 그래도 보여준다(흐리게).

      보은은 관측된 도착이 사실상 전부 일반차량이다. 그때 목록을 통째로 비우면
      화면이 '오는 버스가 없다'처럼 보이고, 실제로 기능이 없는 줄 알았다는 말을 들었다.
      탈 수 없는 버스라도 '지금 뭐가 오는지'는 알아야 다음 판단을 한다 —
      일반차량 3분과 아무것도 없음은 전혀 다른 상황이다.
    */
    const filteredOut = lowOnly && low.length === 0 && all.length > 0;
    const shown = (lowOnly && !filteredOut) ? low : all;

    const box = $('bus-arrivals');
    box.innerHTML = '';

    shown.forEach(a => {
        const li = document.createElement('li');
        const btn = document.createElement('button');
        btn.type = 'button';
        // 걸러졌는데도 보여주는 줄은 흐리게 — 탈 수 있는 버스와 눈으로 구분돼야 한다.
        btn.className = 'bus-arrival' + (filteredOut ? ' is-muted' : '');

        const badge = a.lowFloor === true ? ['is-low', '저상']
            : a.lowFloor === false ? ['is-normal', '일반']
                : ['is-unknown', '확인 불가'];

        // 카운트다운이 1초마다 이 줄을 찾아 남은 시간만 다시 그린다.
        // 원본 arriveSec 을 붙여두고, 흐른 시간은 그릴 때 뺀다.
        if (a.arriveSec != null) {
            btn.dataset.sec = String(a.arriveSec);
            btn.dataset.prev = a.prevStationCount == null ? '' : String(a.prevStationCount);
        }

        btn.innerHTML = `<span class="bus-badge ${badge[0]}">${badge[1]}</span>`
            + `<span class="bus-route-no">${escapeHtml(a.routeNo ?? '노선 미상')}</span>`
            + `<span class="bus-route-tp">${escapeHtml(a.routeType ?? '')}</span>`
            + `<span class="bus-eta">${etaText(a.arriveSec, a.prevStationCount)}</span>`;

        btn.addEventListener('click', () => useArrival(a));
        li.appendChild(btn);
        box.appendChild(li);
    });

    busNote('bus-board-note', boardNote(all, low, lowOnly));

    // ★ 시간표는 도착 목록과 상관없이 늘 그린다. 아래 주석 참고.
    renderLowFloorTimetable();

    // 버스 구간 시간(runMin)이 경유노선에 실려 오므로, 도착판이 온 뒤에 다시 그린다.
    renderLegs();
    renderSeg();

    renderRouteList(all.length === 0);
    renderOtherSide();
}

/**
 * 저상 운행 노선의 시간표. <b>도착 목록이 차 있어도 그린다.</b>
 *
 * <p><b>왜 늘 그리는가</b>: 보은 저상 5개 노선(330·340·410·610·620)은 전부 최근
 * 도입된 전기버스인데 BIS 단말이 연동되지 않아 <b>실시간에 한 번도 잡히지 않는다</b>.
 * 그런데 그 노선이 정확히 휠체어로 탈 수 있는 유일한 버스다.
 * 일반차량 한 대가 오고 있다는 이유로 이 칸을 감추면,
 * <b>탈 수 없는 버스가 탈 수 있는 버스의 시각을 가리는 셈</b>이 된다.
 *
 * <p><b>★ 시각을 '이 정류장 도착'으로 적으면 안 된다.</b> 시간표는 지역명 기준
 * (보은 → 미원)이고 정류장 정보는 TAGO 기준이라, 둘을 잇는 열쇠가 노선번호뿐이다.
 * 기점 출발 시각을 도착 시각처럼 보여주면 사용자는 <b>이미 지나간 버스</b>를 기다린다.
 *
 * <p>그래서 <b>구간으로 적는다</b> — 기점을 12:55 에 떠나 종점까지 30분이 걸린다면,
 * 그 사이 어딘가에 있는 이 정류장은 <b>12:55 ~ 13:25 사이</b>에 지난다. 이건 추정이
 * 아니라 두 끝값 사이라는 사실이고, 사용자가 실제로 쓸 수 있는 형태다.
 */
function renderLowFloorTimetable() {
    const box = $('bus-tt');
    box.innerHTML = '';
    if (!busBoard) return;

    /*
      같은 번호가 여러 번 온다(방향·편성이 나뉜 노선이 번호를 같이 쓴다).
      시간표는 번호 하나에 한 벌이므로 처음 것만 쓴다.
    */
    const byNo = new Map();
    (busBoard.routes || []).forEach(r => {
        const t = r.timetable;
        if (t && t.lowFloorRoute && !byNo.has(t.routeNo)) byNo.set(t.routeNo, t);
    });
    if (!byNo.size) return;

    const head = document.createElement('div');
    head.className = 'bus-tt-head';
    head.textContent = '저상 운행 노선 · 시간표';
    box.appendChild(head);

    [...byNo.values()]
        // 곧 지나가는 노선부터. 기다릴지 정하는 데 제일 먼저 필요한 값이다.
        // 오늘 편이 끝난 노선은 뒤로 밀린다('99:99').
        .sort((a, b) => ((nextTt(a) || {}).hm || '99:99')
            .localeCompare((nextTt(b) || {}).hm || '99:99'))
        .forEach(t => box.appendChild(ttRow(t)));

    /*
      각주를 노선마다 반복하지 않고 한 번만 단다. 반복하면 정작 시각이 묻힌다.
      그렇다고 빼면 안 된다 — '저상 노선'과 '저상차가 온다'는 다른 말이고,
      그 차이를 모르면 사용자가 오지 않는 차를 기다리게 된다.
    */
    const foot = document.createElement('div');
    foot.className = 'bus-tt-foot';
    foot.textContent = '이 노선에 저상차가 다닌다는 뜻입니다. '
        + '어느 차가 저상인지는 실시간 정보가 있어야 알 수 있는데, 이 노선들은 오지 않습니다.';
    box.appendChild(foot);
}

/**
 * 그 노선에서 가장 이른 '다음 차'. 어느 쪽에서 떠나는 편인지를 같이 돌려준다.
 *
 * <p><b>출발지를 빼면 안 된다.</b> '다음 12:55' 만 적으면 사용자는 그것을
 * <b>이 정류장에 오는 시각</b>으로 읽는다. 실제로는 기점을 떠나는 시각이라
 * 그 차는 12:55 에 여기 있지 않다 — 그렇게 읽은 사용자는 이미 지나간 버스를 기다린다.
 */
function nextTt(t) {
    if (!t) return null;
    const a = (t.nextFromOrigin || [])[0];
    const b = (t.nextFromDest || [])[0];

    if (a && (!b || a <= b)) return { hm: a, from: t.originName };
    if (b) return { hm: b, from: t.destName };
    return null;
}

/** 시간표 한 줄. 방향마다 통과 구간을 적는다. */
function ttRow(t) {
    const row = document.createElement('div');
    row.className = 'bus-tt-row';

    const lines = [];
    pushLeg(t.nextFromOrigin, t.originName, t.destName, t.toStopMin);
    pushLeg(t.nextFromDest, t.destName, t.originName, t.fromDestMin);

    /**
     * 한 방향의 다음 차. <b>출발 시각이 아니라 이 정류장을 지나는 시각</b>으로 적는다.
     *
     * @param rideMin 그쪽 끝에서 출발해 여기까지 오는 데 몇 분.
     *                {@code null} 이면 아직 모르는 것이라 구간으로 물러선다
     */
    function pushLeg(times, from, to, rideMin) {
        if (!times || !times.length) return;

        const when = passAt(times[0], rideMin, t.runMin);
        const more = times.length > 1
            ? ` <span class="bus-tt-more">그다음 ${escapeHtml(passAt(times[1], rideMin, t.runMin, true))}</span>`
            : '';
        lines.push(`<div class="bus-tt-leg">`
            + `<span class="bus-tt-dir">${escapeHtml(from || '')}→${escapeHtml(to || '')}</span>`
            + `<span class="bus-tt-time">${when}</span>${more}</div>`);
    }

    // 오늘 편이 다 지나간 노선. 감추지 않고 그렇다고 말한다 —
    // 목록에서 사라지면 사용자는 그런 노선이 없는 줄 안다.
    if (!lines.length) {
        lines.push(`<div class="bus-tt-leg bus-tt-done">오늘은 끝났습니다`
            + (t.todayCount ? ` · 오늘 ${t.todayCount}편` : '') + `</div>`);
    } else if (t.todayCount) {
        lines.push(`<div class="bus-tt-leg bus-tt-count">오늘 ${t.todayCount}편`
            + (t.note ? ` · ${escapeHtml(t.note)}` : '') + `</div>`);
    }

    row.innerHTML = `<span class="bus-route-no">${escapeHtml(t.routeNo)}</span>`
        + `<div class="bus-tt-legs">${lines.join('')}</div>`;
    return row;
}

/**
 * 출발 시각 하나를 <b>이 정류장을 지나는 시각</b>으로 바꾼다.
 *
 * <p>세 단계로 물러선다. 아는 만큼만 말하고, 모르는 것을 그럴듯하게 채우지 않는다.
 *
 * <pre>
 *   ① 여기까지 몇 분인지 안다   11:40 + 2분  →  <b>약 11:42 통과</b>
 *   ② 편도 소요만 안다          11:40 ~ 12:25 사이   (그 안 어딘가)
 *   ③ 그것도 모른다             11:40 출발           (어디서 떠나는지만)
 * </pre>
 *
 * <p>①이 되려면 노선의 경유 정류장을 받아야 한다(서버가 저상 노선만, 한 번에 두 개씩 받는다).
 * 아직 안 받은 노선은 ②로 뜨다가 다음 갱신에서 ①로 바뀐다 — 값이 <b>좁아지는</b> 방향이라
 * 사용자가 보던 시각이 틀린 것으로 바뀌지는 않는다.
 *
 * <p><b>'약' 을 뗄 수 없다.</b> 정류장 사이 거리로 편도 시간을 나눈 추정이라,
 * 구간마다 속도가 다르고 정차 시간도 들어 있다.
 */
function passAt(hm, rideMin, runMin, short) {
    if (rideMin != null) {
        const at = addMinutes(hm, rideMin);
        return short ? escapeHtml(at) : `약 ${escapeHtml(at)} 통과`;
    }
    if (short) {
        return escapeHtml(hm);
    }
    if (runMin == null) {
        return `${escapeHtml(hm)} 출발`;
    }
    return `${escapeHtml(hm)} ~ ${escapeHtml(addMinutes(hm, runMin))} 사이`;
}

/** {@code HH:mm} 에 분을 더한다. 자정을 넘기면 그대로 24시 넘김으로 적는다. */
function addMinutes(hm, min) {
    const [h, m] = hm.split(':').map(Number);
    const v = h * 60 + m + min;
    return `${String(Math.floor(v / 60) % 24).padStart(2, '0')}:${String(v % 60).padStart(2, '0')}`;
}

/**
 * 도착이 없을 때 대신 보여주는 노선 목록.
 *
 * <p><b>노선마다 한 줄씩 끊는다.</b> 한 줄로 이어 쓰면 '320 (~16:10) · 950 (~15:20) · …' 가
 * 문단이 돼서 읽히지 않는다 — 사용자는 자기 노선 하나를 찾는 것이지 전체를 읽는 게 아니다.
 *
 * <p>세 묶음으로 나눈다. 사용자가 알고 싶은 건 '기다리면 오는가, 오늘은 끝났는가' 하나다.
 * 끝난 노선은 줄을 차지할 이유가 없어 개수만 말한다.
 */
function renderRouteList(show) {
    const box = $('bus-routes');
    box.innerHTML = '';

    if (!show || !busBoard) {
        return;
    }

    /*
      같은 번호가 여러 번 온다(방향·편성이 다른 노선이 번호를 같이 쓴다).
      '330 · 330' 으로 찍히면 버그로 읽히니 번호로 합치되,
      운행 시간대는 가장 이른 첫차 ~ 가장 늦은 막차로 넓힌다 —
      330번이 11:40 편성과 14:40~17:40 편성으로 나뉜 것이 그런 경우다.
    */
    const byNo = new Map();
    (busBoard.routes || []).forEach(r => {
        if (!r.routeNo) return;
        const cur = byNo.get(r.routeNo);
        if (!cur) { byNo.set(r.routeNo, { ...r }); return; }
        if (r.running) cur.running = true;
        if (r.firstTime && (!cur.firstTime || r.firstTime < cur.firstTime)) cur.firstTime = r.firstTime;
        if (r.lastTime && (!cur.lastTime || r.lastTime > cur.lastTime)) cur.lastTime = r.lastTime;
        // 시간표는 번호 하나에 한 벌이다. 먼저 온 줄이 비어 있으면 뒷줄 것을 받는다.
        if (!cur.timetable && r.timetable) cur.timetable = r.timetable;
    });

    const routes = [...byNo.values()];
    if (!routes.length) {
        return;
    }

    const nowHm = hhmm(new Date());
    const running = routes.filter(r => r.running === true);
    const later = routes.filter(r => r.running === false && r.firstTime && r.firstTime > nowHm);
    const done = routes.filter(r => r.running === false && !(r.firstTime && r.firstTime > nowHm));

    // 운행시간을 못 받은 경우(TAGO 실패). 번호만이라도 줄 단위로 세운다.
    if (!running.length && !later.length && !done.length) {
        group('이 정류장에 서는 노선', routes.map(r => [r.routeNo, '']));
        return;
    }

    if (running.length) {
        group('지금 다니는 노선',
            running.map(r => [r.routeNo, when(r, r.lastTime ? `${r.lastTime} 까지` : '')]));
    }
    if (later.length) {
        // 곧 오는 것부터. 기다릴지 말지를 정하는 데 제일 먼저 필요한 값이다.
        later.sort((a, b) => a.firstTime.localeCompare(b.firstTime));
        group('아직 첫차 전',
            later.map(r => [r.routeNo, when(r, `${r.firstTime} 부터`)]));
    }
    if (done.length) {
        // 끝난 노선은 줄을 차지할 이유가 없다. 개수만.
        const el = document.createElement('div');
        el.className = 'bus-route-done';
        el.textContent = `오늘 운행이 끝난 노선 ${done.length}개`;
        box.appendChild(el);
    }

    /**
     * 운행 시간대 대신 시간표의 '다음 차'를 적는다. 시간표가 없을 때만 원래 값으로 돌아간다.
     *
     * <p><b>왜 대신 적는가</b>: 첫차·막차는 <b>범위</b>고 이건 <b>편별 시각</b>이다.
     * '19:35 까지' 만 보면 지금부터 19시 반까지 아무 때나 오는 것처럼 읽히는데,
     * 보은은 하루 두세 편이라 그 사이 대부분의 시각에는 오지 않는다.
     * 둘을 나란히 적으면 줄이 길어지기만 하고, 실제로 쓸모 있는 값은 뒤엣것이다.
     *
     * <p><b>출발지를 반드시 같이 적는다</b> — {@link nextTt} 참고.
     * 방향은 가장 이른 한 편만 적는다. 이 목록은 '자기 번호가 있나'를 훑는 자리라
     * 줄이 길어지면 훑기가 안 된다. 자세한 것은 위 저상 시간표 칸이 말한다.
     */
    function when(r, fallback) {
        const n = nextTt(r.timetable);
        return n ? `${n.from ? n.from + ' ' : ''}${n.hm} 출발` : fallback;
    }

    function group(title, rows) {
        const h = document.createElement('div');
        h.className = 'bus-route-group';
        h.textContent = title;
        box.appendChild(h);

        rows.forEach(([no, when]) => {
            const row = document.createElement('div');
            row.className = 'bus-route-row';
            row.innerHTML = `<span class="bus-route-no">${escapeHtml(no)}</span>`
                + `<span class="bus-route-when">${escapeHtml(when)}</span>`;
            box.appendChild(row);
        });
    }
}

/**
 * 같은 이름의 다른 정류장으로 건너가는 길.
 *
 * <p><b>왜 필요한가</b>: 정류장은 방향별로 따로 있고 이름이 같다. 목록은 가까운 순이라
 * 맨 위가 반대 방향일 때가 있는데, 그러면 10m 옆에 버스가 오고 있는데도 화면은 비어 보인다.
 * 실제로 보은여고 497 에는 120번이 오는 중이고 498 에는 아무것도 없었다.
 *
 * <p>도착이 하나도 없을 때만 띄운다. 버스가 오고 있으면 참견할 이유가 없다.
 */
function renderOtherSide() {
    const box = $('bus-other');
    box.innerHTML = '';

    if (!busBoard || (busBoard.arrivals || []).length > 0 || !busStopId) {
        return;
    }
    const here = busStops.find(s => s.stopId === busStopId);
    if (!here) {
        return;
    }
    const others = busStops.filter(s => s.stopName === here.stopName && s.stopId !== busStopId);
    if (!others.length) {
        return;
    }

    box.textContent = '같은 이름의 정류장이 더 있습니다. 반대 방향일 수 있어요 → ';
    others.forEach(s => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'bus-other-btn';
        btn.textContent = `${s.stopName} (${s.distanceM}m)`;
        btn.addEventListener('click', () => pickBusStop(s));
        box.appendChild(btn);
    });
}

/**
 * 남은 시간 한 칸.
 *
 * <p>인자는 <b>받아온 원본 초</b>다. 지금 기준으로 얼마나 남았는지는 여기서 뺀다 —
 * 그래야 1초마다 이 함수만 다시 불러도 값이 맞는다.
 */
function etaText(arriveSec, prevCount) {
    const left = remainSec(arriveSec);
    const prev = (prevCount == null || prevCount === '') ? ''
        : `<small>${prevCount}정거장 전</small>`;

    if (left == null) {
        return '시간 미정';
    }
    /*
      ★ 0 이하가 되면 '0분'이 아니라 '곧 도착'이다.
      숫자가 음수로 내려가거나 0분에 멈춰 있으면 고장으로 보인다.
      실제로 도착한 버스는 다음 갱신(45초) 때 목록에서 빠진다.
    */
    if (left <= 30) {
        return `<b>곧 도착</b>${prev}`;
    }
    // '0분 56초'는 어색하다. 1분이 안 남았으면 초만 말한다.
    if (left < 60) {
        return `<b>${left}초</b>${prev}`;
    }
    // 90초 남았는데 반올림해 '2분'이라 하면 늦는다. 남은 시간은 내림이 맞다.
    return `${Math.floor(left / 60)}분 ${String(left % 60).padStart(2, '0')}초${prev}`;
}

/**
 * 목록 아래 한 줄. <b>비었을 때 왜 비었는지를 말하는 것이 이 줄의 일이다.</b>
 * 그냥 비워두면 사용자는 기능이 고장 난 줄 안다.
 */
function boardNote(all, shown, lowOnly) {
    // 지금 살아 있는 값인지 멈춘 값인지 반드시 구분해서 말한다.
    // 멈춘 줄 모르고 옛 시간을 보고 나가면 버스를 놓친다.
    const at = !busBoardAt ? ''
        : busLive() ? `${hhmm(busBoardAt)} 기준 · 자동 갱신 중`
            : `${hhmm(busBoardAt)} 기준 · 자동 갱신이 멈췄습니다. [새로고침] 을 눌러 주세요`;

    if (!all.length) {
        return emptyNote(at);
    }
    // 걸러도 남는 게 없을 때. 목록은 흐리게라도 보여주고 있으므로 그 사실을 같이 말한다.
    if (lowOnly && !shown.length) {
        return `지금 오는 버스 ${all.length}대는 저상버스가 아닙니다`
            + ` — 아래에 흐리게 표시했습니다. ${at}`;
    }

    const low = all.filter(a => a.lowFloor === true).length;
    return `저상 ${low}대 / 전체 ${all.length}대 · ${at}`;
}

/**
 * 도착이 하나도 없을 때의 안내 <b>한 줄</b>.
 *
 * <p>노선별 내용은 이 줄이 아니라 {@link renderRouteList} 가 목록으로 그린다 —
 * 여기에 이어 쓰면 문단이 돼서 읽히지 않는다(실제로 그랬다).
 */
function emptyNote(at) {
    return `지금 오는 버스가 없습니다. ${at}`;
}

/**
 * 도착 목록에서 버스를 고르면 그 시각을 '버스가 몇 시에 오나요' 칸에 넣는다.
 *
 * <p>이 탭의 두 반쪽(도착 안내 · 나갈 시각)이 만나는 자리다. 사용자가 초를 분으로
 * 고쳐 손으로 옮겨 적을 이유가 없다.
 */
function useArrival(a) {
    // 받아온 뒤 흐른 시간을 뺀 값을 쓴다. 원본 arriveSec 을 그대로 더하면
    // 40초 전에 받은 정보로 '3분 뒤'라고 넣게 돼 그만큼 늦게 나가게 된다.
    const left = remainSec(a.arriveSec);
    if (left == null) {
        setStatus('이 버스는 도착 시각이 없어 자동으로 넣을 수 없습니다.', 'fail');
        return;
    }
    const when = new Date(Date.now() + Math.max(0, left) * 1000);
    $('r-arrive').value = hhmm(when);
    renderLeave();

    setStatus(`${a.routeNo ?? '버스'} ${hhmm(when)} 도착으로 맞췄습니다.`
        + (a.lowFloor === true ? '' : ' 저상버스가 아닙니다.'),
        a.lowFloor === true ? 'ok' : 'fail');
}

/* ── 현재위치 ─────────────────────────────────────────────── */

/** 안내 가능한 지역의 경계 [남서위도, 남서경도, 북동위도, 북동경도]. 서버가 준다. */
let serviceBounds = null;

/**
 * 그 지점이 안내 가능한 지역 안인가.
 *
 * <p>경계에 딱 붙은 곳도 실제로는 쓸 수 있으므로 약간(0.01도, 약 1km) 넉넉하게 본다.
 * 이 판정은 '안내가 되는가'가 아니라 '엉뚱한 데를 보여주고 있는가'를 가리는 용도다.
 */
function insideService(lat, lng) {
    if (!serviceBounds) {
        return true;   // 경계를 모르면 참견하지 않는다
    }
    const m = 0.01;
    const [minLat, minLng, maxLat, maxLng] = serviceBounds;
    return lat >= minLat - m && lat <= maxLat + m
        && lng >= minLng - m && lng <= maxLng + m;
}

/**
 * 화면을 열 때 지도를 사용자 동네로 옮긴다.
 *
 * <p><b>출발지를 채우지 않는다.</b> 그건 [현재위치 찾기] 버튼의 몫이다.
 * 여기서 하는 일은 '보여주는 자리'를 옮기는 것뿐이다 — 이 서비스는 집에서 출발하는 경로를
 * 많이 찾을 텐데, 열자마자 시청이 떠 있으면 매번 자기 동네로 끌고 와야 한다.
 *
 * <p>정확도를 낮게 요청한다({@code enableHighAccuracy: false}). 동네가 보이면 되는 일이라
 * 정밀 측위를 기다릴 이유가 없고, 캐시된 위치({@code maximumAge})를 받으면 즉시 뜬다.
 *
 * <p><b>실패해도 조용히 넘어간다.</b> 사용자가 부른 동작이 아니라서, 권한을 막아둔 사람에게
 * 열 때마다 빨간 오류를 띄우는 것은 참견이다. 그냥 안내 지역 한가운데에 머문다.
 *
 * <p>좌표는 브라우저 안에서만 쓴다. 서버로 보내지 않는다.
 */
function moveToMyArea() {
    if (!navigator.geolocation) {
        return;
    }

    navigator.geolocation.getCurrentPosition(
        (pos) => {
            const lat = pos.coords.latitude, lng = pos.coords.longitude;

            // 이미 출발·도착을 정해 지도를 맞춰둔 뒤에 위치가 도착할 수 있다.
            // 그때 끼어들면 보고 있던 경로가 화면 밖으로 밀린다.
            if (picked.start || picked.end) {
                return;
            }

            map.setCenter(new kakao.maps.LatLng(lat, lng));

            if (!insideService(lat, lng)) {
                setStatus('지금 계신 곳은 아직 안내하지 않는 지역입니다. '
                    + '출발지·도착지를 검색하면 안내 가능한 지역으로 이동합니다.', 'fail');
            }
        },
        () => { /* 권한 거부·시간 초과. 기본 위치에 머문다 */ },
        { enableHighAccuracy: false, timeout: 7000, maximumAge: 600000 }
    );
}

function findHere() {
    if (!navigator.geolocation) {
        setStatus('이 브라우저는 현재위치를 지원하지 않습니다.', 'fail');
        return;
    }

    setStatus('현재위치를 확인하는 중…');

    navigator.geolocation.getCurrentPosition(
        (pos) => {
            const lat = pos.coords.latitude, lng = pos.coords.longitude;
            setPlace('start', { name: '현재 위치', lat, lng });
            map.setCenter(new kakao.maps.LatLng(lat, lng));
            setStatus(picked.end ? '' : '도착지를 정하세요.');
            maybeRoute();
        },
        (err) => setStatus('현재위치를 가져오지 못했습니다. ' +
            (err.code === err.PERMISSION_DENIED ? '위치 권한을 허용해 주세요.' : ''), 'fail'),
        { enableHighAccuracy: true, timeout: 8000 }
    );
}

/* ── 지우기·바꾸기 ────────────────────────────────────────── */

function toggleClear(field, show) {
    const btn = document.querySelector(`.field-clear[data-clear="${field}"]`);
    if (btn) btn.hidden = !show;
}

function clearField(field) {
    picked[field] = null;
    $(field === 'start' ? 'in-start' : 'in-end').value = '';
    toggleClear(field, false);

    const pin = field === 'start' ? startPin : endPin;
    if (pin) pin.setMap(null);
    if (field === 'start') startPin = null; else endPin = null;

    if (routeLine) { routeLine.setMap(null); routeLine = null; }

    // 경로가 사라졌으니 버스 탭의 계산도 근거를 잃는다. 한 곳에서 같이 정리한다.
    lastMeters = 0;
    renderPanes();
}

function clearAll(quiet) {
    clearField('start');
    clearField('end');
    clearResultMarkers();
    $('pane-search').hidden = true;
    if (!quiet) setStatus('출발지와 도착지를 검색해 정하세요.');
}

/** 출발↔도착 교체. 둘 다 차 있으면 바꾼 방향으로 다시 계산한다. */
function swap() {
    const a = picked.start, b = picked.end;
    if (!a && !b) return;

    clearField('start');
    clearField('end');

    if (b) setPlace('start', b);
    if (a) setPlace('end', a);

    maybeRoute();
}

/* ── 정류장 칸 접기 ─────────────────────────────────────────
   ★ 접었는지를 기억한다. 매번 다시 접게 하면 접는 기능이 오히려 짐이 된다.
   기억은 localStorage 에 둔다 — 로그인과 무관한 화면 취향이라 서버에 보낼 값이 아니다. */

const SIDE_KEY = 'wheelway.sideCollapsed';

function applySideCollapsed(collapsed) {
    const side = $('side');
    side.classList.toggle('is-collapsed', collapsed);

    const handle = $('side-handle');
    handle.setAttribute('aria-expanded', String(!collapsed));
    // 손잡이가 '지금 상태'가 아니라 '누르면 무엇이 되는지'를 말해야 한다.
    handle.title = collapsed ? '정류장 칸 펴기' : '정류장 칸 접기';

    /*
      ★ 지도 relayout 은 여기서 부르지 않는다.

      카카오는 지도 폭이 바뀐 것을 스스로 모르니 불러 주기는 해야 하는데,
      그 일은 initMap 의 ResizeObserver(#map)가 이미 하고 있다. 여기서 또
      setTimeout 으로 부르면 <b>시간을 찍어 맞히는 셈</b>이라, 접힘 애니메이션이
      길어지거나(창이 가려지면 실제로 늘어난다) 짧아지면 빗나간다.
      실제 크기가 바뀐 순간을 보는 쪽이 언제나 맞다.
    */
}

function toggleSide() {
    const collapsed = !$('side').classList.contains('is-collapsed');
    localStorage.setItem(SIDE_KEY, collapsed ? '1' : '');
    applySideCollapsed(collapsed);
}

/* ── 화면 조작 ────────────────────────────────────────────── */

function wire() {
    $('side-handle').addEventListener('click', toggleSide);
    applySideCollapsed(localStorage.getItem(SIDE_KEY) === '1');

    bindSuggest('in-start', 'sg-start', 'start');
    bindSuggest('in-end', 'sg-end', 'end');
    bindSuggest('in-search', 'sg-search', null);   // 지도 위 검색바는 이동 전용이다

    $('btn-here').addEventListener('click', findHere);
    $('btn-swap').addEventListener('click', swap);
    $('btn-more').addEventListener('click', loadMore);

    document.querySelectorAll('.field-clear').forEach(btn => {
        btn.addEventListener('click', () => clearField(btn.dataset.clear));
    });

    // 저장된 장소는 로그인이 붙어야 채울 수 있다. 지금은 안내만 띄운다.
    document.querySelectorAll('.shortcut').forEach(btn => {
        btn.addEventListener('click', () =>
            setStatus(`'${btn.textContent.trim()}' 은(는) 로그인 기능이 붙은 뒤에 쓸 수 있습니다.`));
    });

    // 이동수단 탭. 같은 출발·도착에 대해 패널만 바뀐다 —
    // 지도는 길안내, 버스는 나갈 시각, 택시는 아직 자리만.
    // 선택자를 이름으로 좁힌다: .rail-tab 전체를 잡으면 MY·제보까지 걸려서
    // 제보를 눌렀을 때 '제보 길찾기는 준비 중입니다' 가 뜬 뒤 이동한다(실제로 그랬다).
    ['map', 'bus', 'taxi'].forEach(name => {
        const btn = document.querySelector(`.rail-tab[data-tab="${name}"]`);
        if (btn) btn.addEventListener('click', () => selectTab(name));
    });

    // MY 는 화면이 아직 없다. 눌린 것만 보이면 되므로(:active 로 색이 바뀐다) 안내만 남긴다.
    const my = document.querySelector('.rail-tab[data-tab="my"]');
    if (my) {
        my.addEventListener('click', () => setStatus('마이페이지는 아직 준비 중입니다.'));
    }

    // ── 택시 탭 — 기관 고르기
    // 위임으로 건다. 기관이 늘어도 여기를 고칠 일이 없다.
    $('taxi-pick').addEventListener('click', (e) => {
        const btn = e.target.closest('.taxi-org');
        if (!btn) {
            return;
        }
        taxiOrg = btn.dataset.org;
        renderTaxi();
    });

    /*
      서류 챙기기. 자격 버튼과 체크박스 둘 다 여기서 받는다 —
      renderTaxi 가 innerHTML 을 통째로 갈아끼우므로 각 요소에 직접 걸면 매번 사라진다.
    */
    $('taxi-body').addEventListener('click', (e) => {
        const btn = e.target.closest('.taxi-who');
        if (!btn) {
            return;
        }
        taxiWho[taxiOrg] = btn.dataset.who;
        renderTaxi();
    });

    // 체크는 다시 그리지 않는다. 그리면 방금 누른 칸이 화면에서 한 번 튄다.
    $('taxi-body').addEventListener('change', (e) => {
        const box = e.target.closest('input[data-doc]');
        if (!box) {
            return;
        }
        const checks = readDocChecks();
        if (box.checked) {
            checks[box.dataset.doc] = true;
        } else {
            delete checks[box.dataset.doc];      // 지운다. 끈 것까지 쌓아둘 이유가 없다
        }
        localStorage.setItem(TAXI_DOCS_KEY, JSON.stringify(checks));
    });

    // ── 버스 탭 — 주변 정류장·도착 안내
    $('btn-stops').addEventListener('click', loadBusStops);
    // 누르면 자동 갱신 10분이 다시 채워진다(loadBusBoard 안에서). 멈춘 뒤 되살리는 길이기도 하다.
    $('btn-bus-refresh').addEventListener('click', loadBusBoard);

    /*
      창이 가려졌다 돌아오면 화면의 남은 시간이 이미 낡아 있다.
      브라우저가 백그라운드 타이머를 늘려서(실제로 140ms 가 786ms 로 늘어난 적이 있다)
      1초 카운트다운도 제대로 안 돈다. 돌아오는 순간 한 번 새로 받는다.
    */
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && activeTab === 'bus' && busStopId) {
            loadBusBoard();
        }
    });
    // 거르기는 화면에서 한다. 다시 부르면 호출만 늘고 값은 같다.
    $('bus-low-only').checked = lowOnlyPref();
    $('bus-low-only').addEventListener('change', (e) => {
        localStorage.setItem(LOW_ONLY_KEY, e.target.checked ? '1' : '0');
        renderBusBoard();
    });

    /*
        ── 정류장 칸 — 재는 구간 정하기
        가운데 패널의 [현재위치 찾기]·[이 근처 정류장 찾기] 와 <b>같은 함수를 부른다.</b>
        따로 만들면 한쪽만 고쳐 두 버튼이 다르게 도는 상태가 조용히 생긴다.
    */
    $('seg-start-here').addEventListener('click', findHere);
    $('seg-stop-find').addEventListener('click', loadBusStops);

    // ── 버스 탭 — 스톱워치와 역산
    $('r-arrive').addEventListener('input', renderLeave);
    $('sw-btn').addEventListener('click', toggleTrack);
}

wire();
boot();
