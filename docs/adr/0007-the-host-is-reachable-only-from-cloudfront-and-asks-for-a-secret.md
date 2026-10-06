# The host is reachable only from CloudFront and asks for a secret

The host that answers the API is one EC2 instance in a public subnet. Its HTTPS port is open only to the addresses CloudFront reaches origins from, and Caddy refuses any request that does not carry a secret that the distribution alone sends, in a header. CloudFront reaches the host over HTTPS by a name of its own, `origin-catalog.rgonz.dev`, for which Caddy gets a Let's Encrypt certificate by itself.

The two checks answer two different callers. CloudFront's addresses are shared by every CloudFront distribution, so anyone can point a distribution of their own at the host: the address rule keeps the rest of the internet out, and the secret keeps other distributions out.

## Considered Options

- **A CloudFront VPC origin, with the host in a private subnet.** Nothing but CloudFront could reach the host then, and no secret would be needed. But a host in a private subnet reaches nothing outside by itself, and the host has to fetch images, its certificate, and what it sends to AWS. That takes a NAT gateway or a set of interface endpoints, each billed by the hour, for more than the host itself costs.
- **The certificate through a DNS record, with port 80 closed.** Let's Encrypt would check a record at Cloudflare instead of asking the host. That takes a build of Caddy that can change records at Cloudflare, and on the host a token that lets it.
- **An Application Load Balancer in front of the host.** It would hold an AWS certificate and keep the host private, at a fixed monthly price higher than the host's.

## Consequences

- Port 80 is open to everyone, since Let's Encrypt does not say where it checks from. Caddy listens there only while a check is under way, and answers anything else that asks during one with a 404. The rest of the time the port refuses connections.
- The secret is made by Terraform. It is in the Terraform state and in the distribution's settings, and whatever can read either can read it: the role that plans on pull requests reads the state, and the role that publishes the site lists the account's distributions. It is in Parameter Store for the host, and on the host only root can read it. It is not in the repository and not in an output.
- A plan shows neither of the distribution's origins, because one of them holds the secret. A change to an origin is seen in the pull request's diff, and not in its plan.
- Changing the secret takes an apply, which gives it to the distribution and has the host start its stack anew. For the minutes the distribution takes to pass the new one on, requests that still carry the old one are refused.
- The host's address is not given up while a DNS record names it. Whoever is given the address next could get a certificate for the host's name, and would then be sent the secret along with every request to the API. Terraform refuses to release the address, and the record is removed before the host is taken down.
- No SSH port is open. A shell on the host is opened through Systems Manager.
- This is one machine. While it is down, the API is down.
