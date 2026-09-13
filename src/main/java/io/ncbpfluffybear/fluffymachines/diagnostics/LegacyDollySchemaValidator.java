package io.ncbpfluffybear.fluffymachines.diagnostics;

import com.xzavier0722.mc.plugin.slimefun4.storage.callback.IAsyncReadCallback;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;

/** Read-only persistent-state verification for historical Dolly lore bindings. */
final class LegacyDollySchemaValidator {

    private LegacyDollySchemaValidator() {}

    static CompletionStage<Result> validate(String claim) {
        ParsedClaim parsed = parse(claim);
        if (parsed == null) {
            return CompletableFuture.completedFuture(new Result(
                    "MANUAL_ONLY",
                    "Legacy Dolly validation claim could not be interpreted safely.",
                    null));
        }

        CompletableFuture<Result> result = new CompletableFuture<>();
        try {
            Slimefun.getDatabaseManager()
                    .getProfileDataController()
                    .getBackpackAsync(
                            Bukkit.getOfflinePlayer(parsed.owner()),
                            parsed.backpackId(),
                            new IAsyncReadCallback<>() {
                                @Override
                                public void onResult(PlayerBackpack backpack) {
                                    result.complete(classify(parsed, backpack));
                                }

                                @Override
                                public void onResultNotFound() {
                                    result.complete(new Result(
                                            "BACKING_DATA_MISSING",
                                            "The referenced Dolly backing backpack does not exist.",
                                            null));
                                }
                            });
        } catch (RuntimeException exception) {
            result.complete(new Result(
                    "MANUAL_ONLY",
                    "Legacy Dolly backing storage could not be validated safely.",
                    null));
        }
        return result;
    }

    private static Result classify(ParsedClaim parsed, PlayerBackpack backpack) {
        if (backpack == null) {
            return new Result(
                    "BACKING_DATA_MISSING",
                    "The referenced Dolly backing backpack does not exist.",
                    null);
        }
        if (!parsed.owner().equals(backpack.getOwner().getUniqueId()) || parsed.backpackId() != backpack.getId()) {
            return new Result(
                    "STATE_MISMATCH",
                    "The resolved Dolly backing backpack does not match the legacy binding.",
                    null);
        }
        return new Result(
                "VERIFIED",
                "The legacy Dolly binding resolves to an existing backpack with matching ownership and number.",
                backpack.getUniqueId().toString());
    }

    private static ParsedClaim parse(String claim) {
        if (claim == null) return null;
        int separator = claim.lastIndexOf('#');
        if (separator <= 0 || separator >= claim.length() - 1) return null;
        try {
            UUID owner = UUID.fromString(claim.substring(0, separator));
            int backpackId = Integer.parseInt(claim.substring(separator + 1));
            return backpackId < 0 ? null : new ParsedClaim(owner, backpackId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    record Result(String status, String detail, @Nullable String migrationPayload) {}
    private record ParsedClaim(UUID owner, int backpackId) {}
}
