# Keep in sync with relevant parts of .github/workflows/spec.yml

FROM ruby:3.4

WORKDIR /srv/jekyll

COPY Gemfile .
COPY Gemfile.lock .

RUN echo -n "bundle version: " && bundle --version
RUN bundle install
RUN chmod u+s /bin/chown
RUN date
