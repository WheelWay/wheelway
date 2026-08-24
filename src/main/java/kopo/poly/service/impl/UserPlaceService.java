package kopo.poly.service.impl;

import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import kopo.poly.dto.UserPlaceDTO;
import kopo.poly.geocode.IGeocoder;
import kopo.poly.graph.GraphHolder;
import kopo.poly.graph.RouteGraph;
import kopo.poly.mapper.IUserPlaceMapper;
import kopo.poly.service.IUserPlaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 사용자가 등록해둔 장소.
 *
 * <p>핵심은 <b>주소를 좌표로 굳혀서 넣는 것</b>과 <b>못 쓸 좌표를 등록 시점에 막는 것</b> 둘이다.
 *
 * <h3>왜 등록할 때 막아야 하는가</h3>
 * 범위 밖 주소를 그냥 받으면 등록은 조용히 성공하고, 한참 뒤 길찾기를 눌렀을 때 실패한다.
 * 원인(등록)과 증상(길찾기)이 멀리 떨어져 있어서 사용자도 우리도 원인을 못 찾는다.
 * 챗봇이 부산을 {@code matched=false} 로 걸러내는 것과 같은 판단이다.
 *
 * <h3>왜 하필 {@code snap-max-m} 인가</h3>
 * 길찾기가 출발·도착 좌표를 그래프에 붙일 때 쓰는 바로 그 값이다. 여기서 더 너그럽게 받으면
 * <b>'등록은 됐는데 길찾기만 안 되는'</b> 장소가 생긴다. 같은 자로 재야 그 틈이 안 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPlaceService implements IUserPlaceService {

    private final IUserPlaceMapper placeMapper;
    private final IGeocoder geocoder;
    private final GraphHolder graphHolder;

    /** 길찾기가 좌표를 그래프에 붙일 때 쓰는 그 거리. 등록도 같은 자로 잰다. */
    @Value("${wheelway.snap-max-m}")
    private double snapMaxM;

    private static final Set<String> TYPES = Set.of("HOME", "WORK", "FAV");

    /** 라벨을 안 보냈을 때 채워 넣을 이름. {@code FAV} 는 여기 없다 — 이름 없이는 구분이 안 된다. */
    private static String defaultLabel(String placeType) {
        return switch (placeType) {
            case "HOME" -> "집";
            case "WORK" -> "회사";
            default -> null;
        };
    }

    @Override
    public List<UserPlaceDTO> list(String username) {
        List<UserPlaceDTO> places = placeMapper.getPlaces(username);
        places.forEach(this::markUsable);
        return places;
    }

    @Override
    public UserPlaceDTO get(String username, String placeType) {
        UserPlaceDTO place = placeMapper.getPlace(username, normalizeType(placeType));
        if (place != null) {
            markUsable(place);
        }
        return place;
    }

    @Override
    public UserPlaceDTO save(String username, String placeType, String address,
                             String addressDetail, String zonecode, String label) {

        String type = normalizeType(placeType);
        String addr = trimToNull(address);

        if (addr == null) {
            throw new IllegalArgumentException("주소를 입력하세요.");
        }

        String name = trimToNull(label);
        if (name == null) {
            name = defaultLabel(type);
        }
        if (name == null) {
            // FAV 인데 이름이 없다. 이름 없이 넣으면 UQ 가 라벨을 포함하므로
            // 두 번째 즐겨찾기가 첫 번째를 덮어써 버린다.
            throw new IllegalArgumentException("자주 가는 곳은 이름을 함께 입력하세요.");
        }

        // ★ 우편번호·상세주소를 뗀 원문만 넘긴다.
        //   처음에는 "괄호를 붙이면 0건이 난다"고 적어뒀는데 재보니 틀렸다 —
        //   2026-08-21 청주 도로명·지번 8가지로 재봤더니 "(28644)…" 도, 뒤에 "101동 1503호"
        //   를 붙인 것도 전부 같은 좌표를 냈다. 카카오가 견뎌준다.
        //   그래도 원문만 넘기는 이유는 보태는 것이 없고 모든 주소에서 견딘다는 보장이 없어서다.
        IGeocoder.Point point = geocoder.geocode(addr);
        if (point == null) {
            throw new IllegalArgumentException(
                    "'" + addr + "' 의 위치를 찾지 못했습니다. 주소 찾기로 다시 골라 주세요.");
        }

        String regionId = regionOf(point.latitude(), point.longitude());
        if (regionId == null) {
            // 좌표는 나왔는데 우리 그래프가 없는 곳이다. 사용자 잘못이 아니므로 그렇게 말한다.
            throw new IllegalArgumentException(
                    "'" + point.matchedAddress() + "' 은(는) 지금 안내 중인 지역"
                            + "(" + graphHolder.getRegionId() + ") 밖이라 등록할 수 없습니다.");
        }

        UserPlaceDTO dto = new UserPlaceDTO();
        dto.setUsername(username);
        dto.setPlaceType(type);
        dto.setLabel(name);
        dto.setZonecode(trimToNull(zonecode));
        dto.setAddress(addr);
        dto.setAddressDetail(trimToNull(addressDetail));
        dto.setLatitude(point.latitude());
        dto.setLongitude(point.longitude());
        dto.setRegionId(regionId);

        placeMapper.upsertPlace(dto);

        // 방금 넣은 것을 다시 읽어 돌려준다. CREATED_AT 처럼 DB 가 채우는 값과,
        // 덮어쓴 경우의 원래 ID 가 화면에 필요하다.
        UserPlaceDTO saved = placeMapper.getPlace(username, type);
        if (saved == null) {
            // 여기 오면 UPSERT 가 조용히 실패한 것이다. 화면에 성공이라고 말하면 안 된다.
            throw new IllegalStateException("저장한 장소를 다시 읽지 못했습니다.");
        }
        markUsable(saved);

        log.info("장소 등록 {} {} {} → {},{} ({})", username, type, name,
                saved.getLatitude(), saved.getLongitude(), regionId);
        return saved;
    }

    @Override
    public int delete(long id, String username) {
        return placeMapper.deletePlace(id, username);
    }

    // ------------------------------------------------------------------ 내부

    /**
     * 이 좌표가 지금 그래프에 붙는가. 붙으면 그 지역 키를, 아니면 {@code null} 을 준다.
     *
     * <p>bbox 로 재지 않는다. 사각형은 그래프가 없는 구석까지 포함해서, 범위 안인데
     * 길찾기는 실패하는 좌표를 통과시킨다. 실제로 붙여보는 것이 유일하게 정확한 기준이다.
     *
     * <p><b>지금 활성인 지역 하나만 볼 수 있다.</b> 다른 지역의 그래프는 메모리에 없다.
     * 보은 집을 청주 운영 중에 등록하려 하면 막히는데, 그건 지금 정말로 안내를 못 하기 때문이다.
     */
    private String regionOf(double lat, double lon) {
        RouteGraph graph = graphHolder.getGraph();
        if (graph == null) {
            return null;
        }
        return graph.snap(lat, lon, snapMaxM) < 0 ? null : graph.regionId();
    }

    /**
     * 지금 안내 중인 지역의 장소인가.
     *
     * <p>좌표를 다시 붙여보지 않고 저장된 {@code REGION_ID} 만 본다. 목록을 한 번 그릴 때마다
     * 스냅을 돌리면 노드 6만 개를 장소 수만큼 훑게 되고, 등록 시점에 이미 붙여본 값이다.
     */
    private void markUsable(UserPlaceDTO place) {
        place.setUsable(graphHolder.getRegionId().equals(place.getRegionId()));
    }

    /** {@code home} 으로 와도 받는다. 화면이 대소문자를 신경 쓰지 않게 한다. */
    private static String normalizeType(String placeType) {
        String t = (placeType == null) ? "" : placeType.trim().toUpperCase();
        if (!TYPES.contains(t)) {
            throw new IllegalArgumentException("알 수 없는 장소 종류입니다: " + placeType);
        }
        return t;
    }

    /**
     * 빈 문자열은 {@code null} 로 본다.
     *
     * <p>화면이 상세주소를 안 채우면 {@code detail=} 이 빈 값으로 온다. 그대로 두면
     * DB 에 {@code NULL} 과 {@code ''} 두 가지 '없음' 이 생긴다.
     */
    private static String trimToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
