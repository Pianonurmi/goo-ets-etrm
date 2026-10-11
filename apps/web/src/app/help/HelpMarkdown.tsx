import { Anchor, Image } from '@mantine/core';
import { Markdown } from 'markdown-to-jsx/react';
import { useEffect, useMemo, useRef, type ReactNode } from 'react';
import { Link } from 'react-router';
import { helpImage, resolveHelpLink, type HelpPageId } from './routes';
import { slugify } from './slug';

function HelpLink({ href, page, children }: { href?: string; page: HelpPageId; children?: ReactNode }) {
  const target = resolveHelpLink(href ?? '', page);
  if (target.kind === 'external') {
    return (
      <Anchor href={target.to} target="_blank" rel="noopener noreferrer">
        {children}
      </Anchor>
    );
  }
  return (
    <Anchor component={Link} to={target.to}>
      {children}
    </Anchor>
  );
}

function HelpImage({ src, alt }: { src?: string; alt?: string }) {
  return <Image src={helpImage(src ?? '')} alt={alt ?? ''} radius="md" loading="lazy" className="etrm-help-image" />;
}

/**
 * Renders a help page. Raw HTML in the markdown is not parsed (the pages are ours, but nothing needs it and the CSP
 * stays as strict as it is). Links between pages stay in the app; screenshots come from /help/images.
 */
export function HelpMarkdown({ page, source, anchor }: { page: HelpPageId; source: string; anchor?: string }) {
  const root = useRef<HTMLDivElement>(null);
  const options = useMemo(
    () => ({
      disableParsingRawHTML: true,
      slugify: (text: string) => slugify(text),
      overrides: {
        a: { component: HelpLink, props: { page } },
        img: { component: HelpImage },
      },
    }),
    [page],
  );
  useEffect(() => {
    if (!anchor) {
      root.current?.scrollIntoView({ block: 'start' });
      return;
    }
    root.current?.querySelector(`#${CSS.escape(anchor)}`)?.scrollIntoView({ block: 'start' });
  }, [anchor, source]);
  return (
    <div ref={root} className="etrm-help" data-testid="help-content">
      <Markdown options={options}>{source}</Markdown>
    </div>
  );
}
