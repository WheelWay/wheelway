package kopo.poly.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import kopo.poly.dto.WalkRecordDTO;
import kopo.poly.graph.GraphHolder;
import kopo.poly.mapper.IWalkMapper;
import kopo.poly.service.IWalkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 개인 속도 학습.
 *
 * <p><b>평균이 아니라 중앙값을 쓴다.</b> 중간에 카페를 들르거나 [도착] 을 늦게 누른 기록
 * 한 건이 평균을 통째로 끌어당긴다. 중앙값은 그런 값 하나에 거의 흔들리지 않는다.
 * 기록이 10건일 때 한 건이 10배 느려도 중앙값은 제자리다.
 *
 * <p><b>기록이 모자라면 기본값을 쓴다.</b> 한두 건으로 그 사람의 속도라고 하기엔
 * 그날 컨디션·날씨·신호운이 그대로 들어가 있다. 최소 건수를 넘긴 뒤에 넘어간다.
 *
 * <p><b>기본값에 여유시간을 몰래 더하지 않는다.</b> 66.7m/분이 낙관적인 값인 건 맞지만,
 * 화면에 보이는 숫자와 실제 계산이 달라지면 왜 그런지 아무도 설명할 수 없게 된다.
 * 대신 화면에 '아직 기본값'이라고 적고, 실측이 쌓이면 지연이 값 자체에 포함된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalkService implements IWalkService {

    private final IWalkMapper walkMapper;
    private final GraphHolder graphHolder;

    /** 기록이 없을 때 쓸 값. 화면의 WALK_M_PER_MIN 과 같아야 해서 서버가 원본을 갖는다. */
    @Value("${wheelway.walk-default-m-per-min}")
    private double defaultSpeed;

    /** 개인 값으로 넘어가는 데 필요한 기록 수. */
    @Value("${wheelway.walk-min-records}")
    private int minRecords;

    /** 이보다 짧은 기록은 [출발]·[도착] 을 연달아 누른 것으로 본다. */
    @Value("${wheelway.walk-min-sec}")
    private int minSec;

    /** 이보다 긴 기록은 [도착] 누르는 것을 잊은 것으로 본다. */
    @Value("${wheelway.walk-max-sec}")
    private int maxSec;

    @Override
    public List<WalkRecordDTO> list(String username, String stopName) {
        List<WalkRecordDTO> rows = walkMapper.getRecords(username, stopName);
        // 거리를 모르는 기록은 속도를 낼 수 없다. 0 을 채우면 '아주 느림'으로 오해되므로 비워 둔다.
        rows.forEach(r -> r.setSpeedMPerMin(r.getDistanceM() == null ? null : speedOf(r)));
        return rows;
    }

    @Override
    public List<WalkRecordDTO> listAll(String username) {
        if (username == null || username.isBlank()) {
            return List.of();
        }
        List<WalkRecordDTO> rows = walkMapper.getAllRecords(username);
        rows.forEach(r -> r.setSpeedMPerMin(r.getDistanceM() == null ? null : speedOf(r)));
        return rows;
    }

    @Override
    public WalkRecordDTO record(WalkRecordDTO dto) {
        if (dto.getUsername() == null || dto.getUsername().isBlank()) {
            throw new IllegalArgumentException("로그인이 필요합니다.");
        }
        // 거리·좌표는 없어도 된다. 스톱워치는 출발할 때 눌러두는 것이라
        // 위치 권한에 묶이면 본말이 뒤집힌다. 안내에 쓰는 값은 '몇 분 걸렸나'다.
        if (dto.getStartedAt() == null || dto.getArrivedAt() == null) {
            throw new IllegalArgumentException("출발·도착 시각이 모두 있어야 합니다.");
        }
        // 문자열 비교로 충분하다 — 둘 다 'yyyy-MM-dd HH:mm:ss' 라 사전순이 곧 시간순이다.
        if (dto.getArrivedAt().compareTo(dto.getStartedAt()) < 0) {
            throw new IllegalArgumentException("도착이 출발보다 이릅니다.");
        }

        dto.setRegionId(graphHolder.getRegionId());
        walkMapper.insertRecord(dto);

        // ★ 거리는 NULL 일 수 있다(위치를 안 켠 기록). Math.round(Double) 을 그냥 부르면
        //   언박싱하다 NPE 가 난다 — 스톱워치 기록은 거의 전부 이 경우다.
        log.info("이동 기록 #{} — {} · {} · {}, {} → {}",
                dto.getId(), dto.getUsername(),
                dto.getStopName() == null ? "정류장 미지정" : dto.getStopName(),
                dto.getDistanceM() == null ? "거리 없음" : Math.round(dto.getDistanceM()) + "m",
                dto.getStartedAt(), dto.getArrivedAt());

        return dto;
    }

    @Override
    public int setExcluded(long id, String username, boolean excluded) {
        return walkMapper.updateExcluded(id, username, excluded ? "Y" : "N");
    }

    @Override
    public int delete(long id, String username) {
        int n = walkMapper.deleteRecord(id, username);
        // 되돌릴 수 없는 일이라 흔적을 남긴다. 0 이면 남의 기록을 지우려 한 것일 수도 있다.
        log.info("이동 기록 삭제 #{} — {} · 지운 행 {}", id, username, n);
        return n;
    }

    @Override
    public Speed speedOf(String username, String stopName) {
        if (username == null || username.isBlank()) {
            return new Speed(defaultSpeed, null, false, 0, minRecords, null, 0, null, null);
        }

        // 한 번만 읽고 두 가지로 나눠 쓴다. 묶는 단위가 달라서 질의를 나눌 수도 있지만,
        // 사람당 기록이 수십 건 수준이라 왕복을 늘릴 이유가 없다.
        List<WalkRecordDTO> rows = walkMapper.getUsableRecords(username, minSec, maxSec);

        /*
          ★ 안내에 쓰는 값은 '그 정류장까지'만으로 낸다.
          집→정류장A 8분과 집→정류장B 12분을 한 통에 섞으면 중앙값이 둘 다 틀린 값이 된다.
          정류장을 안 고르고 잰 기록(STOP_NAME = NULL)도 하나의 묶음으로 본다.
        */
        List<WalkRecordDTO> mine = rows.stream()
                .filter(r -> java.util.Objects.equals(r.getStopName(), stopName))
                .toList();

        Integer typical = medianMinutes(mine);

        /*
          m/분 은 반대로 기록 전부에서 낸다. 속도는 구간의 성질이 아니라 그 사람의 성질이라
          어느 정류장에서 얻었든 같이 셈하는 것이 맞다.
          거리를 아는 기록에서만 나오므로, 위치를 한 번도 안 켰으면 계속 기본값이다 —
          이 값은 재본 적 없는 구간을 어림할 때만 쓰여서 없어도 크게 아쉽지 않다.
        */
        double[] speeds = rows.stream()
                .filter(r -> r.getDistanceM() != null && r.getDistanceM() > 0 && r.getElapsedSec() > 0)
                .mapToDouble(this::speedOf)
                .sorted()
                .toArray();

        /*
          ★ 한 건만 있어도 그 사람 값을 쓴다.

          이 값이 이 기능의 <b>본체</b>다 — '도보 기준 대비 몇 배'를 알면 그것만으로
          한 번도 안 가본 경로까지 계산된다. 3건이 모일 때까지 아무것도 못 하면
          그 사이에는 비장애인 보행 속도(66.7)로 안내하게 되는데, 그건 한 건짜리
          실측보다 확실히 나쁘다.

          대신 몇 건으로 낸 값인지를 같이 올려서 화면이 "기록 1건 기준"이라고 밝힌다.
          minRecords 는 이제 '충분히 쌓였나'를 표시하는 데만 쓴다.
        */
        boolean any = speeds.length > 0;
        double mPerMin = any ? median(speeds) : defaultSpeed;

        /*
          ★ 도보 기준 대비 배수.

          이 값이 이 기능의 핵심이다 — typicalMinutes 는 재본 정류장에만 쓸 수 있지만,
          배수는 <b>한 번도 안 가본 정류장에도 그대로 적용된다.</b> 시간은 구간의 성질이고
          배수는 그 사람의 성질이라서다. 그래서 정류장으로 좁히지 않은 speeds 에서 낸다.

          모르면 null 이다. 1.0 을 채우면 '차이가 없다'는 말이 되는데,
          그건 '아직 모른다' 와 전혀 다르다.
        */
        Double ratio = any ? round2(defaultSpeed / mPerMin) : null;
        Double low = null;
        Double high = null;
        if (any) {
            // speeds 는 오름차순이다. 속도가 빠를수록 배수는 작으므로 양끝이 뒤집힌다.
            low = round2(defaultSpeed / speeds[speeds.length - 1]);
            high = round2(defaultSpeed / speeds[0]);
        }

        // '값이 충분히 쌓였는가'. 안내를 막는 조건이 아니라 화면에 밝히는 표시다.
        boolean personalized = speeds.length >= minRecords;

        return new Speed(round1(mPerMin), typical, personalized, mine.size(), minRecords,
                ratio, speeds.length, low, high);
    }

    /** 정렬된 배열의 중앙값. 짝수면 가운데 둘의 평균 — 둘 다 실제 관측값이다. */
    private double median(double[] sorted) {
        int n = sorted.length;
        return (n % 2 == 1) ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    /**
     * 잰 구간들의 소요시간 중앙값(분).
     *
     * <p><b>이 기능에서 실제로 쓰이는 값이다.</b> '집에서 정류장까지' 처럼 같은 구간을
     * 반복해서 가는 것을 재는 것이라, 거리를 속도로 나누는 것보다 지난번에 몇 분 걸렸는지가
     * 훨씬 정확하다. 신호 하나, 엘리베이터 한 번까지 그 안에 들어 있다.
     *
     * <p>위치를 안 켜고 잰 기록도 그대로 들어간다 — 시간은 어차피 정확하다.
     */
    private Integer medianMinutes(List<WalkRecordDTO> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        int[] secs = rows.stream().mapToInt(WalkRecordDTO::getElapsedSec).sorted().toArray();
        int n = secs.length;
        double mid = (n % 2 == 1) ? secs[n / 2] : (secs[n / 2 - 1] + secs[n / 2]) / 2.0;

        // 올림한다. '8.2분'을 8분으로 알려주면 매번 조금씩 늦는다.
        return Math.max(1, (int) Math.ceil(mid / 60.0));
    }

    /** 기록 한 건의 속도(m/분). 거리를 아는 기록에만 쓴다. */
    private double speedOf(WalkRecordDTO r) {
        if (r.getElapsedSec() <= 0 || r.getDistanceM() == null) {
            return 0;
        }
        return round1(r.getDistanceM() / (r.getElapsedSec() / 60.0));
    }

    /** 화면에 소수점 한 자리로 보일 값이라 여기서 맞춰둔다. */
    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** 배수는 1.4 와 1.5 의 차이가 뜻을 갖는 값이라 한 자리 더 남긴다. */
    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
