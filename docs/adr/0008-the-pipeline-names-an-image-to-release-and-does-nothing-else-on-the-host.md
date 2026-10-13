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
- The backend is one container. While a release starts the new image, and while a release is taken back, there is no API. Since 2026-10-12 a request waits for one, where it was answered 502 at once: for 26 seconds at first and for 45 now. It is answered 502 if none has come by then, or up to three and a quarter seconds later while a last try to connect ends. CloudFront waits 60 seconds for the host's answer, where it waited 30. [The record of the API's heap](0018-the-apis-heap-is-limited-to-256-mib-chosen-from-reads-of-the-largest-catalog.md) has what is kept and what the two times were chosen from. Three releases were watched with the 26 seconds and a request asked through the site every second, and left the host without an API for 24.4 to 25.3, 23.5 to 24.4, and 27.6 to 28.5 seconds. In the first two, every request asked meanwhile was kept and answered. In the third, that of 2026-10-13 at 04:31 UTC, the request asked first was answered 502 after 26.27 seconds, which was 1.3 seconds before the new API answered, and the 27 after it were kept and answered. None was answered 504. The 45 and the 60 seconds have been on the host and the distribution since 2026-10-13 at 05:17 UTC, after those three releases.
