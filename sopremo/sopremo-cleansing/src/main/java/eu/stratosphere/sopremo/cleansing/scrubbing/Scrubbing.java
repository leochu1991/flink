package eu.stratosphere.sopremo.cleansing.scrubbing;

import java.util.ArrayList;
import java.util.List;

import eu.stratosphere.nephele.configuration.Configuration;
import eu.stratosphere.pact.common.plan.PactModule;
import eu.stratosphere.sopremo.ElementaryOperator;
import eu.stratosphere.sopremo.EvaluationContext;
import eu.stratosphere.sopremo.Name;
import eu.stratosphere.sopremo.Property;
import eu.stratosphere.sopremo.cleansing.fusion.FusionRule;
import eu.stratosphere.sopremo.cleansing.fusion.UnresolvableEvaluationException;
import eu.stratosphere.sopremo.expressions.EvaluationExpression;
import eu.stratosphere.sopremo.expressions.ObjectCreation;
import eu.stratosphere.sopremo.expressions.ObjectCreation.Mapping;
import eu.stratosphere.sopremo.pact.JsonCollector;
import eu.stratosphere.sopremo.pact.SopremoMap;
import eu.stratosphere.sopremo.pact.SopremoUtil;
import eu.stratosphere.sopremo.type.JsonNode;

@Name(verb = "scrub")
public class Scrubbing extends ElementaryOperator<Scrubbing> {
	/**
	 * 
	 */
	private static final long serialVersionUID = 3979039050900230817L;

	private List<ValidationRule> rules = new ArrayList<ValidationRule>();

	public void addRule(final ValidationRule e) {
		this.rules.add(e);
	}

	@Override
	public PactModule asPactModule(final EvaluationContext context) {
		if (this.rules.isEmpty()) {
			final PactModule pactModule = new PactModule(this.getName(), 1, 1);
			pactModule.getOutput(0).setInput(pactModule.getInput(0));
			return pactModule;
		}
		return super.asPactModule(context);
	}

	public List<ValidationRule> getRules() {
		return this.rules;
	}

	public boolean removeRule(final FusionRule o) {
		return this.rules.remove(o);
	}

	public void setRules(final List<ValidationRule> rules) {
		if (rules == null)
			throw new NullPointerException("rules must not be null");

		this.rules = rules;
	}

	@Property
	@Name(preposition = "with")
	public void setRuleExpression(ObjectCreation ruleExpression) {
		this.rules.clear();
		extractRules(ruleExpression, EvaluationExpression.VALUE);
	}

	public ObjectCreation getRuleExpression() {
		return new ObjectCreation();
	}

	private void extractRules(ObjectCreation ruleExpression, EvaluationExpression contextValue) {
		for (Mapping<?> mapping : ruleExpression.getMappings()) {
			for(EvaluationExpression expression : mapping.getExpression())
				this.rules.add(null);
		}
	}

	public static class Implementation extends
			SopremoMap<JsonNode, JsonNode, JsonNode, JsonNode> {
		private List<ValidationRule> rules;

		private transient ValidationContext context;

		@Override
		public void configure(final Configuration parameters) {
			super.configure(parameters);

			this.context = new ValidationContext(this.getContext());
		}

		@Override
		protected void map(final JsonNode key, JsonNode value, final JsonCollector out) {
			try {
				this.context.setContextNode(value);

				for (final ValidationRule rule : this.rules) {
					final List<EvaluationExpression> targetPath = rule.getTargetPath();

					if (targetPath.isEmpty()) {
						if (!rule.validate(value, this.context)) {
							this.context.setViolatedRule(rule);
							value = rule.fix(value, this.context);
						}
					} else {
						JsonNode parent = value;
						final int lastIndex = targetPath.size() - 1;
						for (int index = 0; index < lastIndex; index++)
							parent = targetPath.get(index).evaluate(parent, this.context);

						final EvaluationExpression lastSegment = targetPath.get(lastIndex);
						final JsonNode validationValue = lastSegment.evaluate(parent, this.context);
						if (!rule.validate(validationValue, this.context)) {
							this.context.setViolatedRule(rule);
							lastSegment.set(parent, rule.fix(validationValue, this.context), this.context);
						}
					}
				}
				out.collect(key, value);
			} catch (final UnresolvableEvaluationException e) {
				// do not emit invalid record
				if (SopremoUtil.LOG.isDebugEnabled())
					SopremoUtil.LOG.debug(String.format("Cannot fix validation rule for tuple %s: %s", value,
						e.getMessage()));
			}
		}
	}
}
