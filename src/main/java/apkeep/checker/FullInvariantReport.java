package apkeep.checker;

public final class FullInvariantReport {
    private final long checkedUnits;
    private final long loopUnits;
    private final long blackholeUnits;

    public FullInvariantReport(long checkedUnits, long loopUnits, long blackholeUnits) {
        this.checkedUnits = checkedUnits;
        this.loopUnits = loopUnits;
        this.blackholeUnits = blackholeUnits;
    }

    public long getCheckedUnits() {
        return checkedUnits;
    }

    public long getLoopUnits() {
        return loopUnits;
    }

    public long getBlackholeUnits() {
        return blackholeUnits;
    }
}
