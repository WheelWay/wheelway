package kopo.poly.dto;

import java.util.List;

/**
 * 복합 경로 안내 한 판. <b>'도보만' 안이 반드시 같이 온다.</b>
 *
 * <p><b>왜 나란히 내는가</b>: 기다리는 시간을 빼고 계산하면 버스가 늘 이기는 것처럼 보이는데
 * 실제로는 진다. 보은은 하루 두세 편이라 2km 를 "340번 타세요"라고 하면 세 시간을 기다리게 된다.
 * 청주는 배차가 촘촘해 덜 극단적이지만, 짧은 거리에서는 여전히 걷는 쪽이 이긴다.
 *
 * <p>그래서 버스 안을 냈다는 것만으로 '버스가 낫다'고 말하지 않는다. 둘을 같이 놓고
 * 고르게 한다 — 무엇을 고를지는 그 사람의 사정(날씨·체력·환승 부담)이 정한다.
 *
 * @param walkOnly 처음부터 끝까지 걷는 안. <b>길이 없으면 {@code null}</b> 이다 —
 *                 0 으로 채우면 '걸어서 0m' 라는 거짓이 된다
 * @param plans    버스를 타는 안. 좋은 순. 없으면 빈 목록이고 그때 {@code message} 가 이유를 말한다
 * @param message  버스 안이 없거나 모자란 이유. 화면이 그대로 보여준다.
 *                 <b>'경로 없음'과 '아직 준비 중'은 다른 말이다</b> — 표를 만드는 중이면
 *                 잠시 뒤 다시 눌러보라고 해야 하고, 직통이 없으면 기다려도 안 생긴다
 */
public record TransitResultDTO(TransitPlanDTO.Leg walkOnly,
                               List<TransitPlanDTO> plans,
                               String message) {
}
