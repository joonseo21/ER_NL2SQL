package io.eranalytics.pipeline.collection.persistence;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.invalid;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.collection.BattleUserResultMapper;
import io.eranalytics.pipeline.collection.model.MappedMatch;
import io.eranalytics.pipeline.collection.persistence.entity.AssignedIdEntity;
import io.eranalytics.pipeline.collection.persistence.entity.DeathEntity;
import io.eranalytics.pipeline.collection.persistence.entity.EquipmentEntity;
import io.eranalytics.pipeline.collection.persistence.entity.GameEntity;
import io.eranalytics.pipeline.collection.persistence.entity.MasteryEntity;
import io.eranalytics.pipeline.collection.persistence.entity.MatchupEntity;
import io.eranalytics.pipeline.collection.persistence.entity.ParticipantEntity;
import io.eranalytics.pipeline.collection.persistence.entity.TierEntity;
import io.eranalytics.pipeline.collection.persistence.entity.TraitEntity;
import io.eranalytics.pipeline.collection.persistence.entity.UserEntity;
import io.eranalytics.pipeline.collection.persistence.repository.CharacterRepository;
import io.eranalytics.pipeline.collection.persistence.repository.DeathRepository;
import io.eranalytics.pipeline.collection.persistence.repository.EquipmentRepository;
import io.eranalytics.pipeline.collection.persistence.repository.GameRepository;
import io.eranalytics.pipeline.collection.persistence.repository.MasteryRepository;
import io.eranalytics.pipeline.collection.persistence.repository.MatchupRepository;
import io.eranalytics.pipeline.collection.persistence.repository.ParticipantRepository;
import io.eranalytics.pipeline.collection.persistence.repository.TierRepository;
import io.eranalytics.pipeline.collection.persistence.repository.TraitRepository;
import io.eranalytics.pipeline.collection.persistence.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import io.eranalytics.pipeline.erapi.dto.GameResultDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One game, its users and all children commit together. No HTTP calls are made here. */
@Service
@RequiredArgsConstructor
public class GamePersistenceService {
    private final BattleUserResultMapper mapper;
    private final GameRepository games;
    private final UserRepository users;
    private final ParticipantRepository participants;
    private final TierRepository tiers;
    private final CharacterRepository characters;
    private final EquipmentRepository equipment;
    private final TraitRepository traits;
    private final MasteryRepository mastery;
    private final MatchupRepository matchups;
    private final DeathRepository deaths;
    private final GameWriteLock lock;
    private final Clock clock;

    @Transactional
    public void store(List<JsonNode> results) {
        Map<String, Integer> codes = new HashMap<>();
        characters.findAll().forEach(character -> {
            if (character.getNameEn() != null) {
                codes.merge(character.getNameEn(), character.getCharacterCode(), Math::min);
            }
        });
        persist(mapper.mapMatch(results, codes));
    }

    @Transactional
    public void store(MappedMatch match) {
        persist(match);
    }

    private void persist(MappedMatch match) {
        GameResultDto gameResult = match.game().fields();
        if (gameResult.seasonId() == null) {
            throw invalid("seasonId");
        }
        long gameId = gameResult.gameId();
        int seasonId = gameResult.seasonId();

        // 경기 저장
        lock.acquire("game:" + gameId);
        // Acquire shared users in a stable order before creating or updating any of them.
        List<String> nicknames = match.participants().stream()
                .map(p -> p.data().core().nickname()).distinct().sorted().toList();
        nicknames.forEach(name -> lock.acquire("user:" + seasonId + ":" + name));

        OffsetDateTime now = OffsetDateTime.now(clock);
        GameEntity game = games.findById(gameId).orElseGet(() -> new GameEntity(match.game(), now));
        if (!game.isNew() && !Objects.equals(game.getSeasonId(), seasonId)) {
            // A game's season is part of user identity; never silently move existing history.
            throw invalid("seasonId");
        }
        game.apply(match.game(), now);
        games.saveAndFlush(game);

        // 사용자 확보
        Map<String, UserEntity> userRows = new LinkedHashMap<>();
        for (String name : nicknames) {
            UserEntity user = users.findBySeasonIdAndNickname(seasonId, name)
                    .orElseGet(() -> new UserEntity(seasonId, name, now));
            userRows.put(name, users.save(user));
        }
        List<TierEntity> boundaries = tiers.findByIdSeasonIdOrderByMinMmrDesc(seasonId);
        Map<String, ParticipantEntity> existing = new HashMap<>();
        participants.findByCoreGameId(gameId).forEach(p -> existing.put(p.getCore().getNickname(), p));
        List<ParticipantEntity> saved = new ArrayList<>();
        for (var mapped : match.participants()) {
            var core = mapped.data().core();
            UserEntity user = userRows.get(core.nickname());
            TierEntity boundary = boundary(boundaries, core.mmrBefore());
            ParticipantEntity participant = existing.get(core.nickname());
            if (participant == null) {
                participant = new ParticipantEntity(mapped, user.getUserId(), boundary);
            } else {
                participant.apply(mapped, user.getUserId(), boundary);
            }
            saved.add(participants.save(participant));
        }
        participants.flush(); // Every participant ID exists before resolving deaths.

        for (UserEntity user : userRows.values()) {
            var latest = participants.findLatestGame(user.getUserId(), PageRequest.of(0, 1)).getFirst();
            user.refresh(participants.countByUserId(user.getUserId()), latest, boundary(boundaries, latest.mmr()));
        }
        users.flush();

        List<Long> ids = saved.stream().map(ParticipantEntity::getParticipantId).toList();
        List<EquipmentEntity> equipmentRows = new ArrayList<>();
        List<TraitEntity> traitRows = new ArrayList<>();
        List<MasteryEntity> masteryRows = new ArrayList<>();
        List<MatchupEntity> matchupRows = new ArrayList<>();
        List<DeathEntity> deathRows = new ArrayList<>();
        for (int index = 0; index < match.participants().size(); index++) {
            long id = ids.get(index);
            var mapped = match.participants().get(index);
            mapped.equipment().forEach(row -> equipmentRows.add(new EquipmentEntity(id, row)));
            mapped.traits().forEach(row -> traitRows.add(new TraitEntity(id, row)));
            mapped.mastery().forEach(row -> masteryRows.add(new MasteryEntity(id, row)));
            mapped.matchups().forEach(row -> matchupRows.add(new MatchupEntity(id, row)));
            mapped.deaths().forEach(row -> {
                Integer killerIndex = row.killerParticipantIndex();
                if (killerIndex != null && (killerIndex < 0 || killerIndex >= ids.size())) {
                    throw invalid("killerParticipantIndex");
                }
                deathRows.add(new DeathEntity(id, row, killerIndex == null ? null : ids.get(killerIndex)));
            });
        }
        synchronize(equipment, equipment.findAllByIdParticipantIdIn(ids), equipmentRows, EquipmentEntity::updateFrom);
        synchronize(traits, traits.findAllByIdParticipantIdIn(ids), traitRows, TraitEntity::updateFrom);
        synchronize(mastery, mastery.findAllByIdParticipantIdIn(ids), masteryRows, MasteryEntity::updateFrom);
        synchronize(matchups, matchups.findAllByIdParticipantIdIn(ids), matchupRows, MatchupEntity::updateFrom);
        synchronize(deaths, deaths.findAllByIdParticipantIdIn(ids), deathRows, DeathEntity::updateFrom);
        deaths.flush(); // Fail inside this transaction if any final child violates a DB constraint.
    }

    private static TierEntity boundary(List<TierEntity> boundaries, Integer mmr) {
        return mmr == null ? null : boundaries.stream()
                .filter(t -> t.getMinMmr() <= mmr).findFirst().orElse(null);
    }

    private static <K, E extends AssignedIdEntity<K>> void synchronize(
            JpaRepository<E, K> repository, List<E> existing, List<E> desired, BiConsumer<E, E> update) {
        Map<K, E> remaining = new HashMap<>();
        existing.forEach(row -> remaining.put(row.getId(), row));
        for (E row : desired) {
            E previous = remaining.remove(row.getId());
            if (previous == null) {
                repository.save(row);
            } else {
                update.accept(previous, row);
            }
        }
        repository.deleteAll(remaining.values());
    }
}
