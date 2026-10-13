# The pipeline names an image to release and does nothing else on the host

A push to main puts a new backend on the host. The pipeline builds the commit's image, puts it in the registry, and has the host run one Systems Manager document, to which it passes the commit and nothing else. The document runs the release script that is already on the host. The stack's files, which are the Compose file, the Caddyfile, and that script, reach the host with `terraform apply`, which the maintainer runs by hand.

So merging a change to `deploy/` does not put it on the host; an apply does. A backend that needs a new setting in the Compose file is merged after that apply.

## Considered Options

- **The pipeline sends the stack's files with every deploy**, as commands of its own making. One push to main would then change everything that runs on the host. But a role that can send the host any command is root there: it reads the secrets and the database. Every workflow run on main can take on that role.

## Consequences

- What the deploy role can do on the host is start an image from the backend's repository, in the stack as it was last applied. It can put any image in that repository, so a run on main still decides what code the backend runs.
- To follow a release, the deploy role reads what commands sent through Systems Manager printed, which AWS does not let a policy narrow to one command. It can read that for any command sent in the account.
- The host keeps two images, the one that runs and the one before it. A release that is not healthy within about five minutes is taken back: the image before it is started again and the run fails. The frontend is published after the backend is released, so the frontend built for a backend that was taken back is not published.
- Going back restores the image and not the database. A migration the new image ran stays, which is why every migration leaves the image before it able to run.
- The backend is one container. While a release starts the new image, and while a release is taken back, there is no API. Since 2026-10-12 a request waits for one and is answered 502 if none has come after 26 seconds, or up to three more while a try to connect ends, where it was answered 502 at once; [the record of the API's heap](0018-the-apis-heap-is-limited-to-256-mib-chosen-from-reads-of-the-largest-catalog.md) has what is kept. One release was watched with a request asked through the site every second, that of 2026-10-13 at 01:54 UTC: the host was without an API for 24.4 seconds, or up to a second more, and every request asked meanwhile was kept and answered, none of them 502 or 504. That is 0.7 to 1.6 seconds less than Caddy keeps a request for, and the release of 2026-10-12 at 18:01 UTC left the host without an API for 26 to 27, so a release may not be over in that time, which was not seen.
