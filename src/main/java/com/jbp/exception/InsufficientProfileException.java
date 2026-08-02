package com.jbp.exception;

/**
 * The candidate's profile does not carry enough of their own history for an AI feature to work from
 * — answered 422, and rendered by the apply dialog as design 22b's state G.
 *
 * <p>Its own type rather than a {@link ConflictException}, because the client has to tell it apart
 * from a provider outage: G offers a link to go and fill the profile in, while F offers a retry, and
 * showing the wrong one sends the candidate to do the wrong thing. A 409 would also be wrong on its
 * own terms — nothing conflicts, the request is simply unprocessable as it stands.
 *
 * <p><strong>Two different situations raise this, deliberately.</strong> The structural gate failing
 * (no experience and no project at all), and the gate passing on content too thin to ground an
 * answer, where the assistant declines rather than invents. Both are the same thing to the candidate
 * — "there is nothing here to write from yet" — and neither may be dressed up as a failure of ours.
 */
public class InsufficientProfileException extends RuntimeException {

    public InsufficientProfileException(String message) {
        super(message);
    }
}
