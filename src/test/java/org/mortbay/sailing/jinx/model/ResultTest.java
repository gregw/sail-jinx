package org.mortbay.sailing.jinx.model;

import java.time.Duration;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class ResultTest
{
    @Test
    void elapsedFromActualStartAndFinish()
    {
        Result r = new Result("boat-1", FinishStatus.FIN,
            LocalTime.of(18, 13), LocalTime.of(19, 7, 12), null);
        assertThat(r.elapsed(), equalTo(Duration.ofMinutes(54).plusSeconds(12)));
    }

    @Test
    void elapsedNullWhenStartMissing()
    {
        Result r = new Result("boat-1", FinishStatus.DNC,
            null, null, null);
        assertThat(r.elapsed(), nullValue());
    }

    @Test
    void elapsedNullWhenFinishMissing()
    {
        Result r = new Result("boat-1", FinishStatus.DNF,
            LocalTime.of(18, 0), null, null);
        assertThat(r.elapsed(), nullValue());
    }
}
